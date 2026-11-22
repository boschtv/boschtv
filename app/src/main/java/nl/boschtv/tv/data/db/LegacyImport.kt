package nl.boschtv.tv.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Up to version 2.5 the history, My List, thumbs and favourite channels lived in the catalog database,
 * episodes under the provider's own id and favourites under the provider's channel number. This copies
 * them once into [UserDatabase] under names that hold on every provider. The old tables are left where
 * they are and are simply no longer used.
 */
internal object LegacyImport {

    fun run(catalog: File, into: SupportSQLiteDatabase) {
        if (!catalog.exists()) return
        open(catalog).use { old ->
            // Before profiles existed everything belonged to the household: that becomes profile 1.
            fun profile(table: String) = if ("profileId" in old.columns(table)) "profileId" else "1"

            if (old.has("profiles", "id", "name", "avatar", "color", "createdAt")) runCatching {
                old.rawQuery("SELECT id, name, avatar, color, createdAt FROM profiles", null).each { c ->
                    into.put("profiles", SQLiteDatabase.CONFLICT_IGNORE) {
                        put("id", c.getLong(0)); put("name", c.getString(1)); put("avatar", c.getString(2))
                        put("color", c.getInt(3)); put("createdAt", c.getLong(4))
                    }
                }
            }

            if (old.has("progress", "contentId", "titleKey", "episodeId", "season", "number", "label", "image", "streamId", "container", "positionMs", "durationMs", "finished", "updatedAt")) runCatching {
                // Oldest first: when two rows turn out to be the same episode, the latest one stays.
                old.rawQuery(
                    "SELECT ${profile("progress")}, contentId, titleKey, episodeId, season, number, label, image, streamId, container, " +
                        "positionMs, durationMs, finished, updatedAt FROM progress ORDER BY updatedAt", null,
                ).each { c ->
                    val titleKey = c.getString(2)
                    val season = c.intOrNull(4)
                    val number = c.intOrNull(5)
                    val episode = c.stringOrNull(3) != null
                    val id = ContentIds.fromLegacy(c.getString(1), titleKey, c.stringOrNull(3), season, number) ?: return@each
                    into.put("progress", SQLiteDatabase.CONFLICT_REPLACE) {
                        put("profileId", c.getLong(0)); put("contentId", id); put("titleKey", titleKey)
                        put("season", season.takeIf { episode }); put("number", number.takeIf { episode })
                        put("label", c.stringOrNull(6)); put("image", c.stringOrNull(7))
                        put("streamId", if (c.isNull(8)) null else c.getLong(8)); put("container", c.stringOrNull(9))
                        put("positionMs", c.getLong(10)); put("durationMs", c.getLong(11))
                        put("finished", c.getInt(12)); put("updatedAt", c.getLong(13))
                    }
                }
            }

            if (old.has("my_list", "titleKey", "addedAt")) runCatching {
                old.rawQuery("SELECT ${profile("my_list")}, titleKey, addedAt FROM my_list", null).each { c ->
                    into.put("my_list", SQLiteDatabase.CONFLICT_IGNORE) {
                        put("profileId", c.getLong(0)); put("titleKey", c.getString(1)); put("addedAt", c.getLong(2))
                    }
                }
            }

            if (old.has("ratings", "titleKey", "liked", "at")) runCatching {
                old.rawQuery("SELECT ${profile("ratings")}, titleKey, liked, at FROM ratings", null).each { c ->
                    into.put("ratings", SQLiteDatabase.CONFLICT_IGNORE) {
                        put("profileId", c.getLong(0)); put("titleKey", c.getString(1)); put("liked", c.getInt(2)); put("at", c.getLong(3))
                    }
                }
            }

            // A favourite was the provider's channel number; its name is in the channel list of that moment.
            if (old.has("favorites", "channelId", "addedAt") && old.has("channels", "id", "search", "epgId")) runCatching {
                old.rawQuery(
                    "SELECT ${profile("favorites").let { if (it == "1") it else "f.$it" }}, c.search, c.epgId, f.addedAt " +
                        "FROM favorites f JOIN channels c ON c.id = f.channelId WHERE c.search != ''", null,
                ).each { c ->
                    into.put("favorites", SQLiteDatabase.CONFLICT_IGNORE) {
                        put("profileId", c.getLong(0)); put("channel", c.getString(1))
                        put("epgId", c.stringOrNull(2)?.takeIf { it.isNotBlank() }); put("addedAt", c.getLong(3))
                    }
                }
            }
        }
    }

    /** The catalog may be open elsewhere in the app at the same time; a second connection only reads here. */
    private fun open(file: File): SQLiteDatabase = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
    }.getOrElse { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }

    private fun SQLiteDatabase.columns(table: String): Set<String> =
        rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            buildSet { val name = c.getColumnIndex("name"); while (c.moveToNext()) add(c.getString(name)) }
        }

    private fun SQLiteDatabase.has(table: String, vararg columns: String) = columns(table).containsAll(columns.asList())

    private inline fun Cursor.each(row: (Cursor) -> Unit) = use { while (it.moveToNext()) row(it) }
    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private inline fun SupportSQLiteDatabase.put(table: String, conflict: Int, values: ContentValues.() -> Unit) {
        insert(table, conflict, ContentValues().apply(values))
    }
}
