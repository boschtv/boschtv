package nl.boschtv.tv.data

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.boschtv.tv.R
import nl.boschtv.tv.data.db.UserDatabase
import nl.boschtv.tv.data.db.ProfileEntity
import nl.boschtv.tv.ui.Avatars
import nl.boschtv.tv.ui.Texts

/** The people on this TV, and which of them is watching. There is always at least one. */
class Profiles(private val db: UserDatabase, private val prefs: Prefs, scope: CoroutineScope) {
    private val dao get() = db.profiles()

    /** Every profile in the order they were made; null until the database has answered. */
    val all: StateFlow<List<ProfileEntity>?> = dao.observe().stateIn(scope, SharingStarted.Eagerly, null)

    /** The one watching: the chosen profile, or the first one when that was never chosen or is gone. */
    val active: StateFlow<ProfileEntity?> = combine(dao.observe(), prefs.profile) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Profile 1 is the one the data from before profiles belongs to (see the 5 → 6 migration).
        scope.launch(Dispatchers.IO) {
            if (dao.count() == 0) runCatching { dao.insert(ProfileEntity(id = 1, name = Texts.get(R.string.profile_default), avatar = Avatars.emoji[0], color = 0)) }
        }
    }

    /** The active profile's id; waits for the database on a cold start. */
    suspend fun id(): Long = active.filterNotNull().first().id

    suspend fun ids(): List<Long> = withContext(Dispatchers.IO) { dao.ids() }

    suspend fun select(id: Long) = prefs.saveProfile(id)

    /** Saves [p] (a new one when its id is 0) and returns its id. */
    suspend fun save(p: ProfileEntity): Long = withContext(Dispatchers.IO) {
        val clean = p.copy(name = p.name.trim().take(MAX_NAME))
        if (clean.id == 0L) dao.insert(clean) else { dao.update(clean); clean.id }
    }

    /** Removes a profile and everything it watched, saved and rated. The last profile cannot go. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        if (dao.count() <= 1) return@withContext
        db.withTransaction {
            val lib = db.library()
            lib.clearAllProgress(id); lib.clearList(id); lib.clearRatings(id); lib.clearFavorites(id)
            dao.delete(id)
        }
    }

    companion object {
        const val MAX = 8
        const val MAX_NAME = 20
    }
}
