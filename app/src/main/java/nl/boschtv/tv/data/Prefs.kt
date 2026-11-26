package nl.boschtv.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.boschtv.tv.core.ContentPrefs
import nl.boschtv.tv.data.xtream.Account
import nl.boschtv.tv.data.xtream.Source
import nl.boschtv.tv.data.xtream.Subscription
import nl.boschtv.tv.data.xtream.Subscriptions

private val Context.store by preferencesDataStore(name = "boschtv")

data class AccountState(val expiresAt: Long?, val maxConnections: Int, val trial: Boolean, val problem: String?)

class Prefs(private val context: Context) {
    private object K {
        val HOST = stringPreferencesKey("host")
        val USER = stringPreferencesKey("user")
        val PASS = stringPreferencesKey("pass")
        val ENGLISH = booleanPreferencesKey("english")
        val BELGIAN = booleanPreferencesKey("belgian")
        val ENGLISH_LIVE = booleanPreferencesKey("english_live")
        val LAST_SYNC = longPreferencesKey("last_sync")
        val SYNCED_BY = longPreferencesKey("synced_by")
        val LAST_TRENDS = longPreferencesKey("last_trends")
        val LAST_GUIDE = longPreferencesKey("last_guide")
        val LAST_CHANNEL = longPreferencesKey("last_channel")
        val LAST_GROUP = stringPreferencesKey("last_group")
        val LIVE_EXT = stringPreferencesKey("live_ext")
        val EXPIRES = longPreferencesKey("expires")
        val MAX_CONN = longPreferencesKey("max_conn")
        val TRIAL = booleanPreferencesKey("trial")
        val PROBLEM = stringPreferencesKey("problem")
        val SYNC_REPORT = stringPreferencesKey("sync_report")
        val HOME_LANGUAGE = stringPreferencesKey("home_language")
        val SERVER_ZONE = stringPreferencesKey("server_zone")
        val PREVIEWS = booleanPreferencesKey("previews")
        val PREVIEW_SOUND = booleanPreferencesKey("preview_sound")
        val UPDATE_SNOOZE = stringPreferencesKey("update_snooze")
        val SERVICE_LOGOS = stringPreferencesKey("service_logos")
        val PROFILE = longPreferencesKey("profile")
        val SLEEP_CHECK = booleanPreferencesKey("sleep_check")
        val SUBS = stringPreferencesKey("subscriptions")
        val ACTIVE_SUB = longPreferencesKey("active_subscription")
        /** Everything learned about one provider: forgotten when another subscription takes over. */
        val LEARNED get() = listOf(EXPIRES, MAX_CONN, TRIAL, PROBLEM, SERVER_ZONE, LIVE_EXT, LAST_SYNC, SYNCED_BY, LAST_TRENDS, LAST_GUIDE, SYNC_REPORT, LAST_CHANNEL, LAST_GROUP)
    }

    /** The profile that is watching; null before one was ever chosen. */
    val profile: Flow<Long?> = context.store.data.map { it[K.PROFILE] }.distinctUntilChanged()
    suspend fun saveProfile(id: Long) = context.store.edit { it[K.PROFILE] = id }

    val source: Flow<Source> = context.store.data
        .map { Source(it[K.HOST].orEmpty(), it[K.USER].orEmpty(), it[K.PASS].orEmpty()) }
        .distinctUntilChanged()

    /**
     * Every subscription saved on this TV; [source] is the one in use. An install from before there could
     * be more than one has just its login, which counts as subscription 0.
     */
    val subscriptions: Flow<List<Subscription>> = context.store.data.map { saved(it) }.distinctUntilChanged()
    val activeSubscription: Flow<Int> = context.store.data.map { (it[K.ACTIVE_SUB] ?: 0L).toInt() }.distinctUntilChanged()
    suspend fun activeSubscription(): Int = activeSubscription.first()

    private fun saved(p: androidx.datastore.preferences.core.Preferences): List<Subscription> {
        val list = Subscriptions.decode(p[K.SUBS])
        val current = Source(p[K.HOST].orEmpty(), p[K.USER].orEmpty(), p[K.PASS].orEmpty())
        return if (list.isEmpty() && current.isConfigured) listOf(Subscription((p[K.ACTIVE_SUB] ?: 0L).toInt(), current)) else list
    }

    /** Saves another subscription next to the ones there are (or a new password for one of them). Returns its id. */
    suspend fun addSubscription(s: Source): Int {
        var id = 0
        context.store.edit { val (list, newId) = Subscriptions.with(saved(it), s); it[K.SUBS] = Subscriptions.encode(list); id = newId }
        return id
    }

    /**
     * Makes subscription [id] the one in use. Its catalog is a database of its own, which the app opens
     * when it starts: the caller restarts the app afterwards.
     */
    suspend fun activate(id: Int) = context.store.edit {
        val list = saved(it)
        val sub = list.firstOrNull { s -> s.id == id } ?: return@edit
        it[K.SUBS] = Subscriptions.encode(list)
        it[K.HOST] = sub.source.host; it[K.USER] = sub.source.username; it[K.PASS] = sub.source.password
        it[K.ACTIVE_SUB] = id.toLong()
        K.LEARNED.forEach { key -> it.remove(key) }
    }

    /** Forgets a subscription that is not the one in use. */
    suspend fun removeSubscription(id: Int) = context.store.edit {
        if ((it[K.ACTIVE_SUB] ?: 0L).toInt() == id) return@edit
        it[K.SUBS] = Subscriptions.encode(saved(it).filter { s -> s.id != id })
    }

    val content: Flow<ContentPrefs> = context.store.data
        .map { ContentPrefs(it[K.ENGLISH] ?: true, it[K.BELGIAN] ?: true, it[K.ENGLISH_LIVE] ?: false, nl.boschtv.tv.core.Language.named(it[K.HOME_LANGUAGE])) }
        .distinctUntilChanged()

    val lastSync: Flow<Long> = context.store.data.map { it[K.LAST_SYNC] ?: 0L }.distinctUntilChanged()

    val account: Flow<AccountState> = context.store.data.map {
        AccountState(it[K.EXPIRES], (it[K.MAX_CONN] ?: 1L).toInt(), it[K.TRIAL] ?: false, it[K.PROBLEM])
    }.distinctUntilChanged()

    /** One-line summary of how well the last sync understood the provider's catalog. */
    val syncReport: Flow<String?> = context.store.data.map { it[K.SYNC_REPORT] }.distinctUntilChanged()
    suspend fun saveSyncReport(text: String) = context.store.edit { it[K.SYNC_REPORT] = text }

    suspend fun serviceLogos(): String? = context.store.data.first()[K.SERVICE_LOGOS]
    suspend fun saveServiceLogos(text: String) = context.store.edit { it[K.SERVICE_LOGOS] = text }

    /** The version whose update window was put off with "Later", while that lasts (24 hours). */
    val snoozedUpdate: Flow<String?> = context.store.data.map { p ->
        p[K.UPDATE_SNOOZE]?.split('|')?.takeIf { it.size == 2 && (it[1].toLongOrNull() ?: 0L) > System.currentTimeMillis() }?.get(0)
    }.distinctUntilChanged()
    suspend fun snoozeUpdate(version: String) = context.store.edit { it[K.UPDATE_SNOOZE] = "$version|${System.currentTimeMillis() + 24 * 3600_000L}" }

    /** Hover previews on the home screens: whether they play, and whether with sound. Both on by default. */
    val previews: Flow<Pair<Boolean, Boolean>> = context.store.data
        .map { (it[K.PREVIEWS] ?: true) to (it[K.PREVIEW_SOUND] ?: true) }.distinctUntilChanged()
    suspend fun setPreviews(on: Boolean, sound: Boolean) = context.store.edit { it[K.PREVIEWS] = on; it[K.PREVIEW_SOUND] = sound }

    /** "Are you still watching?" after a few episodes in a row without touching the remote. On by default. */
    val sleepCheck: Flow<Boolean> = context.store.data.map { it[K.SLEEP_CHECK] ?: true }.distinctUntilChanged()
    suspend fun setSleepCheck(on: Boolean) = context.store.edit { it[K.SLEEP_CHECK] = on }

    /** The provider server's time zone for catch-up addresses; null when the provider does not say. */
    suspend fun serverZone(): String? = context.store.data.first()[K.SERVER_ZONE]

    suspend fun source(): Source = source.first()
    suspend fun content(): ContentPrefs = content.first()
    suspend fun lastSync(): Long = lastSync.first()
    /** The app version that made the stored catalog; another version may read the provider's names differently. */
    suspend fun syncedBy(): Long = context.store.data.first()[K.SYNCED_BY] ?: 0L

    suspend fun saveSource(s: Source) = context.store.edit {
        // First login on this TV: start from the TV's own language (changeable in Settings).
        // Installs from before this setting existed have no value and stay Dutch.
        if (it[K.HOME_LANGUAGE] == null && it[K.HOST].isNullOrEmpty()) it[K.HOME_LANGUAGE] = nl.boschtv.tv.core.Language.ofDevice().name
        // The first login, or logging in again after logging out: it takes the catalog that is open now.
        val id = (it[K.ACTIVE_SUB] ?: 0L).toInt()
        it[K.SUBS] = Subscriptions.encode(saved(it).filter { sub -> sub.id != id } + Subscription(id, s))
        it[K.HOST] = s.host; it[K.USER] = s.username; it[K.PASS] = s.password
    }

    suspend fun saveAccount(a: Account) = context.store.edit {
        if (a.expiresAt != null) it[K.EXPIRES] = a.expiresAt else it.remove(K.EXPIRES)
        it[K.MAX_CONN] = a.maxConnections.toLong()
        it[K.TRIAL] = a.trial
        it[K.LIVE_EXT] = if ("m3u8" in a.formats || a.formats.isEmpty()) "m3u8" else "ts"
        if (a.active) it.remove(K.PROBLEM) else it[K.PROBLEM] = a.message
        if (a.timezone != null) it[K.SERVER_ZONE] = a.timezone else it.remove(K.SERVER_ZONE)
    }

    suspend fun setProblem(message: String?) = context.store.edit {
        if (message == null) it.remove(K.PROBLEM) else it[K.PROBLEM] = message
    }

    suspend fun saveContent(c: ContentPrefs) = context.store.edit {
        it[K.ENGLISH] = c.english; it[K.BELGIAN] = c.belgian; it[K.ENGLISH_LIVE] = c.englishLive; it[K.HOME_LANGUAGE] = c.home.name
    }

    suspend fun lastTrends(): Long = context.store.data.first()[K.LAST_TRENDS] ?: 0L
    suspend fun markTrends() = context.store.edit { it[K.LAST_TRENDS] = System.currentTimeMillis() }

    suspend fun lastGuide(): Long = context.store.data.first()[K.LAST_GUIDE] ?: 0L
    suspend fun markGuide() = context.store.edit { it[K.LAST_GUIDE] = System.currentTimeMillis() }

    suspend fun markSynced() = context.store.edit {
        it[K.LAST_SYNC] = System.currentTimeMillis()
        it[K.SYNCED_BY] = nl.boschtv.tv.BuildConfig.VERSION_CODE.toLong()
    }

    suspend fun liveExt(): String = context.store.data.first()[K.LIVE_EXT] ?: "m3u8"
    suspend fun lastChannel(): Pair<Long?, String?> = context.store.data.first().let { it[K.LAST_CHANNEL] to it[K.LAST_GROUP] }
    suspend fun saveLastChannel(id: Long, group: String) = context.store.edit { it[K.LAST_CHANNEL] = id; it[K.LAST_GROUP] = group }

    /**
     * Forgets the IPTV subscription and everything that was learned about that provider. What belongs to
     * the people on this TV stays: language and preview choices, and who is watching. Returns the id of the
     * subscription that takes over when another one is saved, null when this was the only one.
     */
    suspend fun logout(): Int? {
        var next: Int? = null
        context.store.edit {
            // An install from before the language setting existed is Dutch without saying so. Write that down,
            // or the next login would take it for a new install and switch to the TV's own language.
            if (it[K.HOME_LANGUAGE] == null && !it[K.HOST].isNullOrEmpty()) it[K.HOME_LANGUAGE] = nl.boschtv.tv.core.Language.DUTCH.name
            val id = (it[K.ACTIVE_SUB] ?: 0L).toInt()
            val rest = saved(it).filter { s -> s.id != id }
            it[K.SUBS] = Subscriptions.encode(rest)
            (listOf(K.HOST, K.USER, K.PASS) + K.LEARNED).forEach { key -> it.remove(key) }
            // Another subscription is saved: that one takes over (the caller restarts the app onto its catalog).
            rest.firstOrNull()?.let { sub ->
                it[K.HOST] = sub.source.host; it[K.USER] = sub.source.username; it[K.PASS] = sub.source.password
                it[K.ACTIVE_SUB] = sub.id.toLong()
                next = sub.id
            }
        }
        return next
    }
}
