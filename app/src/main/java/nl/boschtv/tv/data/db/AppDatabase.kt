package nl.boschtv.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TitleEntity::class, EpisodeEntity::class, ChannelEntity::class, ChannelGroupEntity::class,
        FavoriteEntity::class, ProgrammeEntity::class, ProgressEntity::class, MyListEntity::class,
        RatingEntity::class, TrendEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun channels(): ChannelDao
    abstract fun library(): LibraryDao
    abstract fun trends(): TrendDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "boschtv2.db")
                .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `trending` (`listId` TEXT NOT NULL, `rank` INTEGER NOT NULL, `titleKey` TEXT NOT NULL, PRIMARY KEY(`listId`, `rank`))")
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
    }
}
