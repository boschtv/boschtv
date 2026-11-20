package nl.boschtv.tv.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.boschtv.tv.BuildConfig
import nl.boschtv.tv.core.asObj
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Keeps sideloaded copies up to date. The app reads a small file next to the published APK:
 *
 *     { "versionCode": 4, "versionName": "2.2.0", "apk": "https://…/boschtv.apk", "notes": "…" }
 *
 * and, when it lists a higher versionCode, downloads that APK and hands it to Android's own package
 * installer, which asks for confirmation. Updates install over the existing app (same signing key),
 * so login, progress and lists stay. Off when the build has no update address.
 */
class Updater(private val app: Context, private val http: OkHttpClient) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val versionName: String, val notes: String?, val apk: String) : State
        data class Downloading(val versionName: String, val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    val enabled get() = BuildConfig.UPDATE_URL.isNotBlank()
    private var lastCheck = 0L

    /** Looks for a newer version; quiet unless one is found. At most once every few hours unless [force]. */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 3 * 3600_000L) return@withContext
        if (_state.value is State.Downloading) return@withContext
        lastCheck = now
        if (force) _state.value = State.Checking
        val result = runCatching {
            // Bypass caches: a stale copy would hide a fresh release.
            val req = Request.Builder().url(BuildConfig.UPDATE_URL).header("Cache-Control", "no-cache").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val o = com.squareup.moshi.JsonReader.of(r.body!!.source()).readJsonValue().asObj() ?: error("Ongeldig")
                val code = o.long("versionCode") ?: error("Geen versie")
                val apk = o.str("apk") ?: error("Geen download")
                if (code > BuildConfig.VERSION_CODE) State.Available(o.str("versionName") ?: "$code", o.str("notes"), apk) else State.UpToDate
            }
        }
        _state.value = result.getOrElse { if (force) State.Failed("Controleren lukte niet") else State.Idle }
    }

    /** Downloads the new version and opens Android's install confirmation. */
    suspend fun install() = withContext(Dispatchers.IO) {
        val available = _state.value as? State.Available ?: return@withContext
        val file = File(app.cacheDir, "update/boschtv.apk").apply { parentFile?.mkdirs(); delete() }
        val ok = runCatching {
            http.newCall(Request.Builder().url(available.apk).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: 1L
                var done = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            _state.value = State.Downloading(available.versionName, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            commit(file)
        }
        if (ok.isFailure) _state.value = State.Failed("Downloaden lukte niet")
        else _state.value = available   // stays offered until the new version is running
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("boschtv.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(app, id, Intent(app, InstallResult::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** Android reports back here; when it needs the user's OK it hands us the confirmation screen to show. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
