package nl.boschtv.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import nl.boschtv.tv.data.Catalog
import nl.boschtv.tv.data.Enricher
import nl.boschtv.tv.data.Epg
import nl.boschtv.tv.data.Library
import nl.boschtv.tv.data.Prefs
import nl.boschtv.tv.data.Recommender
import nl.boschtv.tv.data.SyncManager
import nl.boschtv.tv.data.SyncWorker
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.UserDatabase
import nl.boschtv.tv.data.xtream.USER_AGENT
import nl.boschtv.tv.data.xtream.XtreamClient
import nl.boschtv.tv.system.WatchNext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container: one of everything, reachable through the Application. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    /** The catalog: replaceable. */
    val db = AppDatabase.create(app)
    /** What the people on this TV made themselves: kept through updates, logging out and another provider. */
    val user = UserDatabase.create(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // the full film list is tens of MB
        .retryOnConnectionFailure(true)
        .build()
    val client = XtreamClient(http)
    val catalog = Catalog(db, client, prefs)
    val profiles = nl.boschtv.tv.data.Profiles(user, prefs, scope)
    val library = Library(user, db.channels(), catalog, profiles)
    val trends = nl.boschtv.tv.data.Trends(db, http, prefs, BuildConfig.TMDB_TOKEN)
    val recommender = Recommender(catalog, library, trends)
    init { catalog.completeEpisodes = trends::completeEpisodes }
    val epg = Epg(db, client, prefs)
    val sync = SyncManager(db, client, prefs) { catalog.invalidate() }
    val enricher = Enricher(db, catalog)
    val watchNext = WatchNext(app, catalog, library)
    val updater = nl.boschtv.tv.system.Updater(app, http)
    val skips = nl.boschtv.tv.data.SkipTimes(http, BuildConfig.TMDB_TOKEN)

    init {
        // Another profile starts watching: its own rows everywhere, and its own row on the Google TV home screen.
        scope.launch {
            profiles.active.filterNotNull().map { it.id }.distinctUntilChanged().drop(1).collect {
                library.changed()
                runCatching { watchNext.publish() }
            }
        }
    }

    /**
     * Logging out ends the subscription on this TV, nothing else. The provider's catalog goes (another
     * provider numbers its films, episodes and channels differently); profiles, history, My List, thumbs
     * and favourite channels stay and line up again with the next catalog.
     */
    suspend fun logout() {
        prefs.logout()
        kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { db.clearAllTables() } }
        catalog.invalidate()
        sync.version.value++
        runCatching { watchNext.publish() }
    }

    /** Refresh on launch when the catalog is older than a few hours; never blocks the UI. */
    fun refreshIfStale(force: Boolean = false) = scope.launch {
        launch { runCatching { updater.check() } }   // independent of the catalog: never waits behind it
        launch { runCatching { trends.loadLogos() } }
        val src = prefs.source()
        if (!src.isConfigured) return@launch
        val stale = System.currentTimeMillis() - prefs.lastSync() > 6 * 3600_000L
        // More than one generation means an update was cut off halfway: finish the job.
        val halfDone = runCatching { db.catalog().generations() > 1 }.getOrDefault(false)
        if (force || stale || halfDone || catalog.isEmpty()) {
            if (sync.sync(src).isSuccess) runCatching { library.refreshNextUp() }
        }
        // New trending lists: tell the screens to rebuild their rows.
        if (runCatching { trends.refresh(force) }.getOrDefault(false)) sync.version.value++
        runCatching { enricher.run(maxItems = 200) }
        runCatching { watchNext.publish() }
    }
}

class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        nl.boschtv.tv.ui.Texts.init(resources)
        deleteDatabase("boschtv.db")
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            container.http.newBuilder().readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build()
        }
        .components { add(coil.decode.SvgDecoder.Factory()) }   // for your own SVG service logos
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        .crossfade(120)
        .respectCacheHeaders(false)
        .build()
}
