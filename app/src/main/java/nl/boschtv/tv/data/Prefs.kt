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
        val LAST_TRENDS = longPreferencesKey("last_trends")
        val LAST_CHANNEL = longPreferencesKey("last_channel")
        val LAST_GROUP = stringPreferencesKey("last_group")
        val LIVE_EXT = stringPreferencesKey("live_ext")
        val EXPIRES = longPreferencesKey("expires")
        val MAX_CONN = longPreferencesKey("max_conn")
        val TRIAL = booleanPreferencesKey("trial")
        val PROBLEM = stringPreferencesKey("problem")
        val SYNC_REPORT = stringPreferencesKey("sync_report")
        val HOME_LANGUAGE = stringPreferencesKey("home_language")
        val SERVICE_LOGOS = stringPreferencesKey("service_logos")
    }

    val source: Flow<Source> = context.store.data
        .map { Source(it[K.HOST].orEmpty(), it[K.USER].orEmpty(), it[K.PASS].orEmpty()) }
        .distinctUntilChanged()

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

    suspend fun source(): Source = source.first()
    suspend fun content(): ContentPrefs = content.first()
    suspend fun lastSync(): Long = lastSync.first()

    suspend fun saveSource(s: Source) = context.store.edit {
        // First login on this TV: start from the TV's own language (changeable in Instellingen).
        // Installs from before this setting existed have no value and stay Dutch.
        if (it[K.HOME_LANGUAGE] == null && it[K.HOST].isNullOrEmpty()) it[K.HOME_LANGUAGE] = nl.boschtv.tv.core.Language.ofDevice().name
        it[K.HOST] = s.host; it[K.USER] = s.username; it[K.PASS] = s.password
    }

    suspend fun saveAccount(a: Account) = context.store.edit {
        if (a.expiresAt != null) it[K.EXPIRES] = a.expiresAt else it.remove(K.EXPIRES)
        it[K.MAX_CONN] = a.maxConnections.toLong()
        it[K.TRIAL] = a.trial
        it[K.LIVE_EXT] = if ("m3u8" in a.formats || a.formats.isEmpty()) "m3u8" else "ts"
        if (a.active) it.remove(K.PROBLEM) else it[K.PROBLEM] = a.message
    }

    suspend fun setProblem(message: String?) = context.store.edit {
        if (message == null) it.remove(K.PROBLEM) else it[K.PROBLEM] = message
    }

    suspend fun saveContent(c: ContentPrefs) = context.store.edit {
        it[K.ENGLISH] = c.english; it[K.BELGIAN] = c.belgian; it[K.ENGLISH_LIVE] = c.englishLive; it[K.HOME_LANGUAGE] = c.home.name
    }

    suspend fun lastTrends(): Long = context.store.data.first()[K.LAST_TRENDS] ?: 0L
    suspend fun markTrends() = context.store.edit { it[K.LAST_TRENDS] = System.currentTimeMillis() }

    suspend fun markSynced() = context.store.edit { it[K.LAST_SYNC] = System.currentTimeMillis() }

    suspend fun liveExt(): String = context.store.data.first()[K.LIVE_EXT] ?: "m3u8"
    suspend fun lastChannel(): Pair<Long?, String?> = context.store.data.first().let { it[K.LAST_CHANNEL] to it[K.LAST_GROUP] }
    suspend fun saveLastChannel(id: Long, group: String) = context.store.edit { it[K.LAST_CHANNEL] = id; it[K.LAST_GROUP] = group }

    suspend fun logout() = context.store.edit { it.clear() }
}
