package nl.boschtv.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TitleEntity::class, EpisodeEntity::class, ChannelEntity::class, ChannelGroupEntity::class,
        FavoriteEntity::class, ProgrammeEntity::class, ProgressEntity::class, MyListEntity::class,
        RatingEntity::class, TrendEntity::class, ProfileEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun channels(): ChannelDao
    abstract fun library(): LibraryDao
    abstract fun trends(): TrendDao
    abstract fun profiles(): ProfileDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "boschtv2.db")
                .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `trending` (`listId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, PRIMARY KEY(`listId`, `rank`))")
                    }
                }, object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `titles` ADD COLUMN `lPlot` TEXT")
                        db.execSQL("ALTER TABLE `titles` ADD COLUMN `lPlotLocale` TEXT")
                    }
                }, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
    }
}

/**
 * Profiles: history, My List, thumbs and favourite channels get a profile id. Everything that was there
 * goes to profile 1, which [nl.boschtv.tv.data.Profiles] creates on first start.
 */
private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `avatar` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        fun rebuild(table: String, create: String, columns: String, indices: List<String> = emptyList()) {
            db.execSQL("ALTER TABLE `$table` RENAME TO `${table}_old`")
            db.execSQL(create)
            db.execSQL("INSERT INTO `$table` (`profileId`, $columns) SELECT 1, $columns FROM `${table}_old`")
            db.execSQL("DROP TABLE `${table}_old`")
            indices.forEach { db.execSQL(it) }
        }
        db.execSQL("DROP INDEX IF EXISTS `index_progress_titleKey`")
        db.execSQL("DROP INDEX IF EXISTS `index_progress_updatedAt`")
        rebuild(
            "progress",
            "CREATE TABLE IF NOT EXISTS `progress` (`profileId` INTEGER NOT NULL, `contentId` TEXT NOT NULL, `titleKey` TEXT NOT NULL, `episodeId` TEXT, `season` INTEGER, `number` INTEGER, `label` TEXT, `image` TEXT, `streamId` INTEGER, `container` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `contentId`))",
            "`contentId`, `titleKey`, `episodeId`, `season`, `number`, `label`, `image`, `streamId`, `container`, `positionMs`, `durationMs`, `finished`, `updatedAt`",
            listOf(
                "CREATE INDEX IF NOT EXISTS `index_progress_profileId_titleKey` ON `progress` (`profileId`, `titleKey`)",
                "CREATE INDEX IF NOT EXISTS `index_progress_profileId_updatedAt` ON `progress` (`profileId`, `updatedAt`)",
            ),
        )
        rebuild("my_list", "CREATE TABLE IF NOT EXISTS `my_list` (`profileId` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `titleKey`))", "`titleKey`, `addedAt`")
        rebuild("ratings", "CREATE TABLE IF NOT EXISTS `ratings` (`profileId` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, `liked` INTEGER NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `titleKey`))", "`titleKey`, `liked`, `at`")
        rebuild("favorites", "CREATE TABLE IF NOT EXISTS `favorites` (`profileId` INTEGER NOT NULL, `channelId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `channelId`))", "`channelId`, `addedAt`")
    }
}
