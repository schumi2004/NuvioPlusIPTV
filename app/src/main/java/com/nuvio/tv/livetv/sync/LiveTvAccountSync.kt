package com.nuvio.tv.livetv.sync

import android.content.Context
import android.os.Build
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings screen shows. */
data class LiveTvSyncState(
    /** False on a server that isn't yours (the official one): the setup holds IPTV logins. */
    val available: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    /** The server's host name, to show where the setup is saved. */
    val server: String? = null,
    val autoSync: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L
)

@Serializable
private data class IptvSyncRow(
    val payload: JsonObject,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Syncs the Live TV setup through your own self-hosted Nuvio server (NuvioMedia/self-host plus
 * selfhost/migrations/90000000000001_iptv_sync.sql), using the Nuvio account you are signed in
 * with. No separate sign-in, and nothing goes to Google or the official Nuvio servers.
 *
 * Sync is "newest wins": changes are backed up shortly after they happen, and a newer copy from
 * another TV is applied when the app starts, when you sign in, or when you tap Restore. Copies are
 * merged setting by setting and source by source, so two TVs never overwrite each other.
 */
@Singleton
class LiveTvAccountSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val repository: LiveTvRepository,
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    serverConfiguration: ServerConfiguration
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.getSharedPreferences("livetv_account_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Only a self-hosted server gets the setup: one picked on the sign-in screen, whose discovery
     * document says self_hosted. Never the built-in server, which in release builds is Nuvio's.
     */
    private val serverIsOwn = serverConfiguration.isCustom
    private val serverHost = serverConfiguration.backendUrl.toHttpUrlOrNull()?.host

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<LiveTvSyncState> = _state.asStateFlow()

    private var started = false
    /** True while a restored setup is being written, so it isn't backed up straight back. */
    @Volatile private var applying = false
    /** When a restore or sync last wrote this TV's setup (its own changes come after that). */
    @Volatile private var appliedAtMs = 0L
    @Volatile private var emptyRefused = false

    /** The copy on the server: when it was saved, on which TV, and whether that was this one. */
    data class BackupInfo(val savedAtMs: Long, val device: String, val fromThisTv: Boolean)

    /** About the saved copy, for the restore prompt (null: none, or the server couldn't be reached). */
    suspend fun backupInfo(): BackupInfo? = withContext(Dispatchers.IO) {
        if (!canSync()) return@withContext null
        val json = remoteCopy() ?: return@withContext null
        BackupInfo(json.optLong("savedAt"), json.optString("device").ifBlank { "another TV" }, json.optString("deviceId") == deviceId)
    }

    /** When this TV's setup was last changed on this TV (0: not since this version). */
    fun localChangedAt(): Long = store.getLong(KEY_LOCAL_CHANGED_AT, 0L)

    private val deviceId: String
        get() = store.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            store.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private fun loadState(): LiveTvSyncState {
        val account = authManager.authState.value as? AuthState.FullAccount
        return LiveTvSyncState(
            available = serverIsOwn,
            connected = serverIsOwn && account != null,
            email = account?.email,
            server = serverHost,
            autoSync = store.getBoolean(KEY_AUTO, true),
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    private fun canSync() = serverIsOwn && authManager.isAuthenticated

    /** Starts automatic sync: follow the account, check for a newer setup, back up local changes. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (!serverIsOwn) return
        // Signed in (at start-up, or just now) or signed out. Loading is skipped.
        scope.launch {
            authManager.authState
                .map { state ->
                    when (state) {
                        is AuthState.FullAccount -> state
                        AuthState.SignedOut -> SIGNED_OUT
                        AuthState.Loading -> null
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { state -> onAccountChanged(state as? AuthState.FullAccount) }
        }
        // When this TV's own setup last changed (not by a restore or sync): the restore prompt
        // warns when the saved copy is older than that.
        scope.launch {
            prefs.changes.drop(1).collect {
                val now = System.currentTimeMillis()
                if (!applying && now - appliedAtMs > 20_000) store.edit().putLong(KEY_LOCAL_CHANGED_AT, now).apply()
            }
        }
        scope.launch {
            prefs.changes.drop(1).debounce(8_000).collect {
                val s = _state.value
                if (s.connected && s.autoSync && !applying) push(force = false)
            }
        }
    }

    private suspend fun onAccountChanged(account: AuthState.FullAccount?) {
        if (account == null) {
            _state.value = _state.value.copy(connected = false, email = null, message = null)
            return
        }
        _state.value = _state.value.copy(connected = true, email = account.email)
        if (store.getString(KEY_USER_ID, null) != account.userId) {
            // Another account than the one this TV last synced with: start over, so nothing of
            // the previous account's copy is merged into this one.
            store.edit()
                .putString(KEY_USER_ID, account.userId)
                .remove(KEY_LAST_HASH)
                .remove(KEY_APPLIED_AT)
                .apply()
            runCatching { baseFile().delete() }
            // First connection: use the server's copy if there is one, otherwise back this TV up.
            if (!pull(force = true, onlyIfExists = true)) push(force = true)
        } else if (_state.value.autoSync) {
            pull(force = false)
        }
    }

    fun setAutoSync(enabled: Boolean) {
        store.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.value = _state.value.copy(autoSync = enabled)
    }

    fun backUpNow() { scope.launch { push(force = true) } }
    fun restoreNow() { scope.launch { pull(force = true) } }

    // ------------------------------------------------------------------ backup / restore

    /** Uploads this TV's setup. Skips the upload when nothing changed since the last one. */
    private suspend fun push(force: Boolean) = mutex.withLock {
        if (!canSync()) return@withLock
        emptyRefused = false
        _state.value = _state.value.copy(busy = true, message = if (force) "Backing up…" else null)
        val result = runCatching {
            var data = prefs.exportForSync()
            val removedHere = prefs.removedSourceIds()
            val hash0 = sha(data.toString())
            if (!force && hash0 == store.getString(KEY_LAST_HASH, null)) return@runCatching false
            // Another TV saved since we last synced: combine its changes with ours first, so
            // neither TV overwrites the other. Decided by what's in the copy, not by the TVs' clocks.
            readRemote()?.let { remote ->
                val remoteData = remote.optJSONObject("data")
                val base = readBase()
                if (remoteData != null && remoteData.toString() != base?.toString()) {
                    val merged = merge(base, local = data, remote = remoteData, localWinsWithoutBase = true, removedHere = removedHere)
                    if (merged.toString() != data.toString()) applyLocally(merged)
                    data = merged
                }
            }
            // No playlists and no guides here (signed out, a fresh install, everything removed):
            // never write that over the backup. Automatic sync just skips it; Back up now says why.
            if (!prefs.syncHasSources(data)) {
                if (force) emptyRefused = true
                return@runCatching false
            }
            val hash = sha(data.toString())
            val now = System.currentTimeMillis()
            val body = JSONObject()
                .put("format", 1)
                .put("savedAt", now)
                .put("deviceId", deviceId)
                .put("device", Build.MODEL ?: "TV")
                .put("data", data)
                .toString()
            val params = buildJsonObject {
                put("p_payload", json.parseToJsonElement(body))
            }
            withJwtRefreshRetry { postgrest.rpc("iptv_sync_push", params) }
            store.edit().putString(KEY_LAST_HASH, hash).putLong(KEY_LAST_SYNC, now).putLong(KEY_APPLIED_AT, now).apply()
            writeBase(data)
            // Those removals are in the saved copy now.
            prefs.forgetRemovedSources(removedHere)
            true
        }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Backup failed: ${describe(result.exceptionOrNull())}"
                emptyRefused -> "Nothing backed up: add a playlist or guide first. The copy on your server was left as it was."
                force -> "Backed up to your server."
                else -> _state.value.message
            }
        )
    }

    /**
     * Applies the server's copy if it's newer than what this TV has (or always, when [force]).
     * Returns true if a copy was found and applied.
     */
    private suspend fun pull(force: Boolean, onlyIfExists: Boolean = false): Boolean = mutex.withLock {
        if (!canSync()) return@withLock false
        _state.value = _state.value.copy(busy = true, message = if (force && !onlyIfExists) "Restoring…" else null)
        val result = runCatching {
            val json = readRemote() ?: return@runCatching null
            val savedAt = json.optLong("savedAt")
            val fromThisTv = json.optString("deviceId") == deviceId
            val remoteData = json.optJSONObject("data") ?: return@runCatching false
            // Nothing new: saved by this TV, or the same as what this TV last synced.
            val base = readBase()
            if (!force && (fromThisTv || remoteData.toString() == base?.toString())) return@runCatching false
            // A saved copy with no playlists or guides: never let it empty this TV.
            if (!prefs.syncHasSources(remoteData)) return@runCatching null
            // "Restore" replaces this TV's setup; automatic sync keeps changes made on this TV
            // since the last sync and takes everything else from the other TV.
            val data = if (force) remoteData
            else merge(base, local = prefs.exportForSync(), remote = remoteData, localWinsWithoutBase = false, removedHere = prefs.removedSourceIds())
            applyLocally(data)
            store.edit()
                .putLong(KEY_APPLIED_AT, savedAt)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_LAST_HASH, sha(remoteData.toString()))
                .apply()
            writeBase(remoteData)
            // Our own changes were kept on top: send the combined setup back up.
            if (data.toString() != remoteData.toString()) scope.launch { delay(2_000); push(force = false) }
            true
        }
        val applied = result.getOrNull() == true
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Restore failed: ${describe(result.exceptionOrNull())}"
                result.getOrNull() == null && force && !onlyIfExists -> "No backup found on your server yet."
                applied && force -> "Restored from your server."
                applied -> "Updated from your other TV."
                else -> _state.value.message
            }
        )
        applied
    }

    // ------------------------------------------------------------------ merging

    private suspend fun applyLocally(data: JSONObject) {
        applying = true
        appliedAtMs = System.currentTimeMillis()
        try {
            val before = prefs.exportForSync()
            val changed = prefs.importFromSync(data)
            val after = prefs.exportForSync()
            // What really changed here. Nothing: no reload at all.
            val keys = (before.keys().asSequence() + after.keys().asSequence()).toSet()
                .filter { k -> before.opt(k)?.toString() != after.opt(k)?.toString() }
            if (keys.isEmpty() && changed.isEmpty()) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Sync: other TV's setup is the same as this one; nothing to reload")
                return
            }
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Sync: applied changes from your other TV (${keys.take(6).joinToString()}${if (keys.size > 6) "…" else ""})")
            // Download any playlists or guides this TV doesn't have yet, playlists whose address
            // or login changed on the other TV, and rebuild the guide.
            repository.reloadAfterSync(changed)
            delay(500)
        } finally {
            applying = false
            appliedAtMs = System.currentTimeMillis()
        }
    }

    /** The server's copy, or null if there isn't one or it can't be read. */
    private suspend fun remoteCopy(): JSONObject? = runCatching { readRemote() }.getOrNull()

    /** The server's copy, or null if there isn't one. Throws when the server can't be reached. */
    private suspend fun readRemote(): JSONObject? {
        val response = withJwtRefreshRetry { postgrest.rpc("iptv_sync_pull") }
        val row = response.decodeList<IptvSyncRow>().firstOrNull() ?: return null
        return JSONObject(row.payload.toString())
    }

    /**
     * Combines two setups setting by setting: whatever this TV changed since the last sync
     * ([base]) is kept, everything else comes from the other TV. Without a base, [localWinsWithoutBase]
     * decides.
     */
    private fun merge(
        base: JSONObject?,
        local: JSONObject,
        remote: JSONObject,
        localWinsWithoutBase: Boolean,
        removedHere: Set<String>
    ): JSONObject {
        if (base == null) {
            // Nothing synced on this TV yet: one side's settings win, but playlists and guides
            // from both are kept.
            val (first, second) = if (localWinsWithoutBase) local to remote else remote to local
            val out = JSONObject(first.toString())
            for (k in SOURCE_LISTS) {
                val a = first.optJSONObject(k)
                val b = second.optJSONObject(k)
                if (b == null) continue
                if (a == null) { out.put(k, JSONObject(b.toString())); continue }
                runCatching { unionSources(a.optString("v"), b.optString("v"), removedHere) }.getOrNull()
                    ?.let { out.put(k, JSONObject(a.toString()).put("v", it)) }
            }
            return out
        }
        val out = JSONObject(remote.toString())
        val keys = HashSet<String>().apply {
            local.keys().forEach { add(it) }
            base.keys().forEach { add(it) }
        }
        keys.forEach { k ->
            val l = local.opt(k)?.toString()
            val b = base.opt(k)?.toString()
            if (l != b) {
                // Playlists and guides are lists: combine them source by source, setting by
                // setting. A list missing on one side counts as empty (never "remove everything").
                val listMerged = if (k in SOURCE_LISTS && (local.has(k) || remote.has(k))) {
                    runCatching {
                        mergeSources(
                            base.optJSONObject(k)?.optString("v"),
                            local.optJSONObject(k)?.optString("v") ?: "[]",
                            remote.optJSONObject(k)?.optString("v") ?: "[]",
                            removedHere
                        )
                    }.getOrNull()
                } else null
                val shell = (local.optJSONObject(k) ?: remote.optJSONObject(k))
                when {
                    listMerged != null && shell != null -> out.put(k, JSONObject(shell.toString()).put("v", listMerged))
                    local.has(k) -> out.put(k, local.get(k))
                    else -> out.remove(k)
                }
            }
        }
        return out
    }

    /**
     * Three-way merge of two source lists (JSON arrays of objects with an "id"): sources added
     * or removed on either TV are added or removed, and within a source each setting changed on
     * this TV since the last sync is kept, everything else comes from the other TV.
     */
    private fun mergeSources(baseRaw: String?, localRaw: String, remoteRaw: String, removedHere: Set<String>): String {
        val base = readSources(baseRaw)
        val local = readSources(localRaw)
        val remote = readSources(remoteRaw)
        val result = org.json.JSONArray()
        // The other TV's order, with its additions and edits.
        for ((id, r) in remote) {
            val l = local[id]
            val b = base[id]
            when {
                // Removed on this TV (by you) since the last sync: stays removed.
                l == null && id in removedHere -> continue
                // Missing here but never removed here: kept. Only a removal someone actually
                // made takes a source off the other TVs.
                l == null -> result.put(r)
                b == null -> result.put(r) // On both TVs but new to the last sync: the other TV's.
                else -> {
                    val merged = JSONObject(r.toString())
                    l.keys().forEach { f ->
                        if (l.opt(f)?.toString() != b.opt(f)?.toString()) merged.put(f, l.get(f))
                    }
                    result.put(merged)
                }
            }
        }
        // Added on this TV since the last sync.
        for ((id, l) in local) {
            if (id !in remote && id !in base) result.put(l)
        }
        return result.toString()
    }

    private fun readSources(raw: String?): LinkedHashMap<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        if (raw.isNullOrBlank()) return out
        val arr = org.json.JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out[id] = o
        }
        return out
    }

    /**
     * Two source lists combined with nothing synced to compare against: every source in [firstRaw]
     * (its versions win), then those only in [secondRaw], except ones removed on this TV.
     */
    private fun unionSources(firstRaw: String, secondRaw: String, removedHere: Set<String>): String {
        val first = readSources(firstRaw)
        val result = org.json.JSONArray()
        first.values.forEach { result.put(it) }
        for ((id, o) in readSources(secondRaw)) {
            if (id !in first && id !in removedHere) result.put(o)
        }
        return result.toString()
    }

    private fun baseFile() = java.io.File(context.filesDir, "livetv_sync_base.json")

    private fun readBase(): JSONObject? = runCatching { JSONObject(baseFile().readText()) }.getOrNull()

    private fun writeBase(data: JSONObject) {
        runCatching { baseFile().writeText(data.toString()) }
    }

    // ------------------------------------------------------------------ server plumbing

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /** Plain-English reason, so a network or server problem shows. */
    private fun describe(e: Throwable?): String = when (e) {
        null -> "network error"
        is java.net.SocketTimeoutException -> "your server timed out"
        is java.net.UnknownHostException -> "couldn't look up your server's address"
        is javax.net.ssl.SSLException -> "secure connection refused"
        is java.net.ConnectException -> "connection refused"
        else -> e.message?.lineSequence()?.firstOrNull()?.take(160) ?: e.javaClass.simpleName
    }

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Settings that are lists of sources, merged source by source. */
        val SOURCE_LISTS = setOf("playlists", "epg_sources")
        /** Stands in for "signed out" in the account flow (a plain null means "still loading"). */
        val SIGNED_OUT = Any()
        const val KEY_AUTO = "auto_sync"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_APPLIED_AT = "applied_at"
        const val KEY_LOCAL_CHANGED_AT = "local_changed_at"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_USER_ID = "user_id"
    }
}
