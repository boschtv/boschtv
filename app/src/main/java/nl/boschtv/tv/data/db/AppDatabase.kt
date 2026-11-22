package nl.boschtv.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The catalog: what the IPTV provider offers, plus what TMDB says about it. All of it can be fetched
 * again, so a version without a migration simply starts over. What people made themselves is in [UserDatabase].
 */
@Database(
    entities = [
        TitleEntity::class, EpisodeEntity::class, ChannelEntity::class, ChannelGroupEntity::class,
        ProgrammeEntity::class, TrendEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun channels(): ChannelDao
    abstract fun trends(): TrendDao

    companion object {
        const val NAME = "boschtv2.db"

        /** Each saved subscription has a catalog of its own, so switching between them needs no rebuilding. */
        fun nameFor(subscription: Int) = if (subscription == 0) NAME else "boschtv2-$subscription.db"

        fun create(context: Context, subscription: Int = 0): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, nameFor(subscription))
                .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `trending` (`listId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, PRIMARY KEY(`listId`, `rank`))")
                    }
                }, object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `titles` ADD COLUMN `lPlot` TEXT")
                        db.execSQL("ALTER TABLE `titles` ADD COLUMN `lPlotLocale` TEXT")
                    }
                }, USER_DATA_MOVED_OUT(5), USER_DATA_MOVED_OUT(6), object : androidx.room.migration.Migration(7, 8) {
                    // Up to 2.5.8 an episode list could outlive a switch to another version of its series
                    // (after a change of content language): drop them all once, they are fetched again.
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("DELETE FROM episodes")
                        db.execSQL("UPDATE titles SET detailAt = 0")
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
    }
}

/**
 * Version 7: history, My List, thumbs, favourites and profiles moved to [UserDatabase], which copies them
 * over itself ([LegacyImport]). Their old tables stay here untouched, so the catalog is kept and nothing
 * is thrown away on the way; this database just no longer looks at them.
 */
@Suppress("FunctionName")
private fun USER_DATA_MOVED_OUT(from: Int) = object : androidx.room.migration.Migration(from, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
}
