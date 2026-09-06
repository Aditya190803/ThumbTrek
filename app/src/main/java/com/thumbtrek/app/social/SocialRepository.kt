package com.thumbtrek.app.social

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.thumbtrek.app.R
import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.monthKey
import com.thumbtrek.app.stats.weekKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Which stretch of time a board covers. */
enum class Period(val label: String) {
    WEEK("This week"),
    MONTH("This month"),
    ALL_TIME("All time"),
}

/**
 * One board row, already reduced to the ranked unit. Micrometres, not pixels: rows now
 * come from phones of different densities and from browsers, and only µm compare (contract
 * §1). A row that predates the change is read through [rankUm], which is the only place
 * legacy pixels are still interpreted.
 */
data class LeaderboardEntry(
    val uid: String,
    val displayName: String,
    val photoUrl: String,
    val weekUm: Long,
    val monthUm: Long,
    val totalUm: Long,
) {
    fun score(period: Period): Long = when (period) {
        Period.WEEK -> weekUm
        Period.MONTH -> monthUm
        Period.ALL_TIME -> totalUm
    }
}

/** Someone who added your code and is waiting for you to accept. */
data class FriendRequest(val uid: String, val displayName: String, val photoUrl: String)

/** One of last week's top three, from the weekly archive. */
data class PodiumEntry(val displayName: String, val photoUrl: String, val um: Long)

/** What a sync learned about my own row: who is writing into it, and when they last did. */
data class SyncStatus(
    val sources: List<SourceTotals> = emptyList(),
    val lastSyncedAt: Long? = null,
)

/**
 * Firestore caps `whereIn` at 30 values, so friend lists are queried in chunks.
 */
private const val QUERY_CHUNK = 30

/** Global board page size; [MAX_PAGES] bounds how far "load more" can walk. */
private const val PAGE_SIZE = 50
private const val MAX_PAGES = 8

/**
 * The archive is ordered on `pixels` (every row has one) and re-ranked here, so a week
 * holding a mix of pre- and post-µm rows still produces the right three. Cheap: one page
 * of tiny documents, once, for a block that shows three names.
 */
private const val PODIUM_SCAN = 20

/**
 * Google Sign-In (Credential Manager) + score sync to Firestore, implementing the client
 * half of `docs/sync-protocol.md`. The phone is no longer the only writer: a browser
 * extension writes into the same board row, so every write here is scoped to what this
 * source owns.
 *
 * users/{uid} = { displayName, photoUrl, friendCode, weekKey, monthKey,
 *                 weekPixels, monthPixels, totalPixels,   // legacy, Android only
 *                 weekUm, monthUm, totalUm,               // combined — what ranks
 *                 sources: { android: {...}, web: {...} } }
 * users/{uid}/days/{date}__android = { date, source, um, apps, updatedAt } — the private
 * per-day ledger the web dashboard charts, and what makes history survive a reinstall.
 * users/{uid}/friends/{friendUid} = { since, status } — status is "accepted" or
 * "pending"; edges written before requests existed have no status field and read as
 * accepted, so old friendships survive without migration.
 * friendCodes/{code} = { uid } — the invite lookup index.
 * archive/{weekKey}/scores/{uid} = { displayName, photoUrl, pixels, um } — one frozen copy
 * of each week's board so last week's podium survives the reset (scores are otherwise
 * overwritten in place when weekKey rolls over).
 *
 * Nothing here writes anything until [Prefs.leaderboardOptIn] is on (PRD §11 Q2):
 * signing in by itself publishes no score.
 */
class SocialRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = Prefs.get(context)
    private val credentialManager = CredentialManager.create(context)

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid = _uid.asStateFlow()

    private var registeredCodeFor: String? = null

    /** Server-side pagination cursor for the global board; null once exhausted. */
    private var globalCursor: DocumentSnapshot? = null
    private var globalExhausted = false

    val displayName: String get() = auth.currentUser?.displayName ?: "Trekker"
    val photoUrl: String get() = auth.currentUser?.photoUrl?.toString() ?: ""

    /** What the board actually shows: the Google identity, or a stable pseudonym. */
    val publishedName: String
        get() {
            val current = auth.currentUser?.uid ?: return displayName
            return if (prefs.anonymous.value) anonymousHandle(current) else displayName
        }

    val publishedPhotoUrl: String get() = if (prefs.anonymous.value) "" else photoUrl

    val myFriendCode: String? get() = auth.currentUser?.uid?.let(::friendCode)

    /**
     * The density every local pixel count was measured at.
     *
     * `densityDpi` is a property of the *device*, not of a row: every row in Room was
     * recorded on this phone at this density, so converting the entire local history with
     * today's value is correct for the life of an install rather than an approximation
     * that gets worse the further back you look. This looks like a bug when you meet it
     * cold, which is why it is written down.
     *
     * (Changing the system "display size" setting does move `densityDpi`. Rows recorded
     * before the change would then convert at the new density. Recording a per-row density
     * was considered and dropped: it means a Room migration and a wider row to correct an
     * error smaller than the one this whole change removes, and there is no way to recover
     * the density of rows already written.)
     */
    private val densityDpi: Int get() = context.resources.displayMetrics.densityDpi

    /** Read once per sync so the whole publish uses one consistent set of keys. */
    private fun identityFields(uid: String): Map<String, Any> = mapOf(
        "displayName" to publishedName,
        "photoUrl" to publishedPhotoUrl,
        "friendCode" to friendCode(uid),
        "weekKey" to weekKey(),
        "monthKey" to monthKey(),
    )

    /** [activityContext] must be an Activity: Credential Manager renders UI over it. */
    suspend fun signIn(activityContext: Context) {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = credentialManager.getCredential(activityContext, request)
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        _uid.value = auth.currentUser?.uid
    }

    fun signOut() {
        auth.signOut()
        registeredCodeFor = null
        resetGlobalCursor()
        _uid.value = null
    }

    /**
     * Publishes this phone's three totals and re-rolls the combined figures (contract §3).
     *
     * The phone is the source of truth for its *own* numbers only. It writes
     * `sources.android` and never another source's key — that single rule is what makes a
     * concurrent extension sync safe, and it is why the old wholesale write of
     * `totalPixels` had to go: with two writers that was last-write-wins data loss.
     *
     * Returns who is currently writing into the row, so the UI can show the phone/browser
     * split. No-op until the user opts in.
     */
    suspend fun syncScore(weekPixels: Long, monthPixels: Long, totalPixels: Long): SyncStatus {
        val current = auth.currentUser?.uid ?: return SyncStatus()
        if (!prefs.leaderboardOptIn.value) return SyncStatus()
        val dpi = densityDpi
        val mine = SourceTotals(
            source = SOURCE_ANDROID,
            weekKey = weekKey(),
            weekUm = pixelsToMicrometres(weekPixels, dpi),
            monthKey = monthKey(),
            monthUm = pixelsToMicrometres(monthPixels, dpi),
            totalUm = pixelsToMicrometres(totalPixels, dpi),
        )
        val identity = identityFields(current)
        val doc = db.collection("users").document(current)

        // Step 1: my own subtree, plus the legacy pixel fields. Those are still written for
        // the sake of *readers*: a phone on an old build ranks the whole board on
        // `weekPixels`, and if this row stopped carrying one it would read as zero there.
        // They come out again the day the last pre-µm client is gone (contract §1).
        //
        // `sources` is written as a nested map under SetOptions.merge(), which derives its
        // field mask from the *leaves* — so this touches sources.android.weekUm and friends
        // and leaves sources.web exactly as the extension left it, the same guarantee the
        // contract's dotted paths describe. A plain set() of a whole `sources` map would
        // replace the sibling entry, which is the bug this migration exists to fix.
        doc.set(
            identity + mapOf(
                "weekPixels" to weekPixels,
                "monthPixels" to monthPixels,
                "totalPixels" to totalPixels,
                "sources" to mapOf(
                    SOURCE_ANDROID to mapOf(
                        // This source's own period keys, not the row's: once a row has
                        // several writers the row-level keys say nothing about whether any
                        // one source's weekly figure is still current (contract §3).
                        "weekKey" to mine.weekKey,
                        "weekUm" to mine.weekUm,
                        "monthKey" to mine.monthKey,
                        "monthUm" to mine.monthUm,
                        "totalUm" to mine.totalUm,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ),
            ),
            SetOptions.merge(),
        ).await()

        // Step 2: re-read and sum every source whose own period key is still current.
        // Losing a race here is harmless — the combined figure is briefly stale and either
        // client's next sync corrects it. The per-source numbers, which are the real
        // record, are never wrong.
        val fresh = doc.get().await()
        val sources = if (fresh.exists()) {
            parseSources(fresh.sourcesMap()).replacing(mine)
        } else {
            // Deleted between the two calls — an opt-out from the extension takes the whole
            // row with it. Nothing to sum but my own.
            listOf(mine)
        }
        val combined = combine(sources, weekKey(), monthKey())
        mergeOrRecreate(
            partial = mapOf(
                "weekKey" to weekKey(),
                "monthKey" to monthKey(),
                "weekUm" to combined.weekUm,
                "monthUm" to combined.monthUm,
                "totalUm" to combined.totalUm,
            ),
            identity = identity,
            rejected = ::isRulesRejection,
        ) { doc.set(it, SetOptions.merge()).await() }

        // Freeze this week's row into the archive so the podium outlives the reset. `um` is
        // what the podium ranks on; `pixels` stays for rows frozen before the unit change.
        db.collection("archive").document(weekKey()).collection("scores").document(current)
            .set(
                mapOf(
                    "displayName" to publishedName,
                    "photoUrl" to publishedPhotoUrl,
                    "pixels" to weekPixels,
                    "um" to combined.weekUm,
                ),
                SetOptions.merge(),
            ).await()
        registerFriendCode(current)
        return SyncStatus(sources, prefs.lastSyncedAt.takeIf { it > 0L })
    }

    /**
     * Pushes the per-day, per-app ledger the web dashboard charts (contract §3).
     *
     * First sync after opting in has nothing recorded as pushed, so this backfills the
     * whole local history; afterwards it writes only the days whose totals moved. Writes go
     * in batches because a user with two years of history must not fire 700 requests, and
     * each batch is recorded as it lands so a failure half-way through a backfill resumes
     * rather than starting over.
     */
    suspend fun syncDays(rows: List<DailyScroll>) {
        val current = auth.currentUser?.uid ?: return
        if (!prefs.leaderboardOptIn.value) return
        val pending = dirtyDays(dayLedgers(rows, densityDpi), prefs.syncedDayUm)
        val days = db.collection("users").document(current).collection("days")
        for (chunk in pending.chunked(BATCH_LIMIT)) {
            val batch = db.batch()
            chunk.forEach { day ->
                // set, not merge: one source owns the document outright, so there is no
                // other writer's field to preserve and a removed app should disappear.
                batch.set(
                    days.document(day.documentId(SOURCE_ANDROID)),
                    mapOf(
                        "date" to day.date,
                        "source" to SOURCE_ANDROID,
                        "um" to day.um,
                        "apps" to day.apps,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.commit().await()
            prefs.markDaysSynced(chunk.associate { it.date to it.um })
        }
        prefs.lastSyncedAt = System.currentTimeMillis()
    }

    /**
     * Publishes the code → uid mapping invites resolve against. Best effort: a code
     * already claimed by another account must not break score sync.
     */
    private suspend fun registerFriendCode(current: String) {
        if (registeredCodeFor == current) return
        runCatching {
            db.collection("friendCodes").document(friendCode(current))
                .set(mapOf("uid" to current))
                .await()
        }.onSuccess { registeredCodeFor = current }
    }

    /**
     * Opting back out withdraws everything this phone published. Friend edges survive a
     * later opt-in.
     *
     * The private day ledger goes first: deleting `users/{uid}` does *not* delete its
     * subcollections, and an orphaned day-by-day history sitting on the server after the
     * user asked to leave is precisely what opting out is supposed to prevent.
     *
     * The board row itself is only deleted when this phone is the last writer. Contract §5
     * says opting out deletes `users/{uid}`, which was true when the phone was the only
     * client — but doing that while the extension is still publishing would destroy its
     * data too, the same clobber this whole migration removes. So when another source is
     * present we withdraw `sources.android` and the legacy pixel fields (which were only
     * ever this phone's) and re-roll the combined figures from what is left.
     */
    suspend fun unpublish() {
        val current = auth.currentUser?.uid ?: return
        deleteMyDays(current)
        val doc = db.collection("users").document(current)
        val others = parseSources(doc.get().await().sourcesMap())
            .filterNot { it.source == SOURCE_ANDROID }
        if (others.isEmpty()) {
            doc.delete().await()
        } else {
            val combined = combine(others, weekKey(), monthKey())
            doc.update(
                mapOf(
                    "sources.$SOURCE_ANDROID" to FieldValue.delete(),
                    "weekPixels" to FieldValue.delete(),
                    "monthPixels" to FieldValue.delete(),
                    "totalPixels" to FieldValue.delete(),
                    "weekUm" to combined.weekUm,
                    "monthUm" to combined.monthUm,
                    "totalUm" to combined.totalUm,
                ),
            ).await()
        }
        prefs.clearSyncState()
    }

    /** Every day document this source wrote, in batches. Nobody else's ids match. */
    private suspend fun deleteMyDays(current: String) {
        val days = db.collection("users").document(current).collection("days")
        val mine = days.whereEqualTo("source", SOURCE_ANDROID).get().await().documents
        for (chunk in mine.chunked(BATCH_LIMIT)) {
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    // --- friends ---------------------------------------------------------------

    /** Accepted friends only; edges predating request semantics count as accepted. */
    suspend fun friendUids(): List<String> {
        val current = auth.currentUser?.uid ?: return emptyList()
        return friendsOf(current).get().await().documents
            .filter { it.getString(FIELD_STATUS) != STATUS_PENDING }
            .map { it.id }
    }

    /** Requests I've received and not answered yet, newest bookkeeping aside. */
    suspend fun incomingRequests(): List<FriendRequest> {
        val current = auth.currentUser?.uid ?: return emptyList()
        val pending = friendsOf(current).get().await().documents
            .filter { it.getString(FIELD_STATUS) == STATUS_PENDING }
            .map { it.id }
        if (pending.isEmpty()) return emptyList()
        return pending.chunked(QUERY_CHUNK).flatMap { chunk ->
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
                .documents
                .map { FriendRequest(it.id, entryName(it), entryPhoto(it)) }
        }
    }

    /**
     * Sends a trek request by writing both edges as pending. Either endpoint may later
     * flip them to accepted or delete them — that's what makes acceptance possible
     * without any edge being editable by an outsider (see firestore.rules).
     */
    suspend fun addFriendByCode(input: String) {
        val current = auth.currentUser?.uid ?: error("Sign in to add friends")
        val code = normalizeFriendCode(input)
        require(code.length == FRIEND_CODE_LENGTH) {
            "That code doesn't look right — friend codes are $FRIEND_CODE_LENGTH characters."
        }
        require(code != friendCode(current)) { "That's your own code." }
        val friend = (db.collection("friendCodes").document(code).get().await()
            .get("uid") as? String).orEmpty()
        require(friend.isNotEmpty()) { "No trekker has that code yet." }
        require(friend != current) { "That's your own code." }

        val mine = friendsOf(current).document(friend).get().await()
        require(!mine.exists()) {
            if (mine.getString(FIELD_STATUS) == STATUS_PENDING) {
                "Request already sent — waiting on them."
            } else {
                "You're already trekking together."
            }
        }
        val edge = mapOf(
            "since" to FieldValue.serverTimestamp(),
            FIELD_STATUS to STATUS_PENDING,
        )
        val batch = db.batch()
        batch.set(friendsOf(current).document(friend), edge)
        batch.set(friendsOf(friend).document(current), edge)
        batch.commit().await()
    }

    /** Accepts a request: flips both pending edges to accepted in one batch. */
    suspend fun acceptFriend(friendUid: String) =
        respondToFriend(friendUid, STATUS_ACCEPTED)

    /** Declines a request, or removes an existing friend — both drop both edges. */
    suspend fun removeFriend(friendUid: String) =
        respondToFriend(friendUid, null)

    private suspend fun respondToFriend(friendUid: String, newStatus: String?) {
        val current = auth.currentUser?.uid ?: return
        val batch = db.batch()
        if (newStatus == null) {
            batch.delete(friendsOf(current).document(friendUid))
            batch.delete(friendsOf(friendUid).document(current))
        } else {
            // merge, not update: the other side's edge may already be gone if they
            // removed us first — recreating a bare accepted edge keeps things symmetric.
            val edge = mapOf(FIELD_STATUS to newStatus)
            batch.set(friendsOf(current).document(friendUid), edge, SetOptions.merge())
            batch.set(friendsOf(friendUid).document(current), edge, SetOptions.merge())
        }
        batch.commit().await()
    }

    // --- boards ----------------------------------------------------------------

    /**
     * Me plus [friends], client-sorted for every period (friend lists are small enough
     * that chunked `whereIn` beats a composite index). A friend whose doc still carries
     * last period's key shows as zero rather than dropping off.
     */
    suspend fun friendBoard(friends: List<String>, period: Period): List<LeaderboardEntry> {
        val current = auth.currentUser?.uid ?: return emptyList()
        val ids = (listOf(current) + friends).distinct()
        val entries = mutableListOf<LeaderboardEntry>()
        val dpi = densityDpi
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
                .documents
                .mapTo(entries) { it.toEntry(dpi) }
        }
        return entries.sortedByDescending { it.score(period) }
    }

    /**
     * First page of the global board for [period], server-ordered (needs the composite
     * indexes documented in firestore.rules / README).
     */
    suspend fun openGlobalBoard(period: Period): List<LeaderboardEntry> {
        resetGlobalCursor()
        return globalPage(period)
    }

    /** Next page, or empty once exhausted. Only valid after [openGlobalBoard]. */
    suspend fun nextGlobalPage(period: Period): List<LeaderboardEntry> {
        if (globalExhausted) return emptyList()
        return globalPage(period)
    }

    /** True while another page might exist for the currently open global board. */
    fun globalHasMore(): Boolean = !globalExhausted

    /**
     * Ordered on the µm fields — the only unit that compares across clients (contract §1).
     *
     * Firestore's `orderBy` skips documents that lack the field, so a row still published
     * by a pre-µm build is not on this page at all. That is accepted rather than worked
     * around: the alternative is running the legacy query as a second pass and merging two
     * cursors, which doubles reads on every page for rows that reappear the moment their
     * owner opens an updated app. Friend boards, which use `whereIn` and sort client-side,
     * keep showing them throughout — that is where a migrating user notices.
     */
    private suspend fun globalPage(period: Period): List<LeaderboardEntry> {
        var query: Query = db.collection("users")
        when (period) {
            Period.WEEK -> query = query
                .whereEqualTo("weekKey", weekKey())
                .orderBy("weekUm", Query.Direction.DESCENDING)
            Period.MONTH -> query = query
                .whereEqualTo("monthKey", monthKey())
                .orderBy("monthUm", Query.Direction.DESCENDING)
            Period.ALL_TIME -> query = query
                .orderBy("totalUm", Query.Direction.DESCENDING)
        }
        query = query.limit(PAGE_SIZE.toLong())
        globalCursor?.let { query = query.startAfter(it) }
        val snap = query.get().await()
        globalCursor = snap.documents.lastOrNull()
        globalExhausted = snap.size() < PAGE_SIZE || globalCursor == null
        val dpi = densityDpi
        return snap.documents.map { it.toEntry(dpi) }
    }

    private fun resetGlobalCursor() {
        globalCursor = null
        globalExhausted = false
    }

    /**
     * Top three of last week's frozen board. Empty before archives exist.
     *
     * Ordered on `pixels` and re-ranked on µm client-side. Ordering on `um` directly would
     * be one query fewer but would drop every row frozen before the unit change, emptying
     * the podium for the migration weeks; `pixels` is the field every archive row has.
     */
    suspend fun lastWeekPodium(): List<PodiumEntry> {
        val last = weekKey(LocalDate.now().minusWeeks(1))
        val dpi = densityDpi
        return db.collection("archive").document(last).collection("scores")
            .orderBy("pixels", Query.Direction.DESCENDING)
            .limit(PODIUM_SCAN.toLong())
            .get()
            .await()
            .documents
            .map { doc ->
                PodiumEntry(
                    entryName(doc),
                    entryPhoto(doc),
                    rankUm(doc.longOrNull("um"), doc.longOrZero("pixels"), dpi),
                )
            }
            .sortedByDescending { it.um }
            .take(3)
    }

    private fun friendsOf(owner: String) =
        db.collection("users").document(owner).collection("friends")

    companion object {
        const val FIELD_STATUS = "status"
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"

        fun entryName(doc: DocumentSnapshot): String =
            (doc.getString("displayName"))?.takeIf { it.isNotBlank() } ?: "Trekker"

        fun entryPhoto(doc: DocumentSnapshot): String = doc.getString("photoUrl").orEmpty()
    }
}

private fun DocumentSnapshot.longOrNull(key: String): Long? = (get(key) as? Number)?.toLong()

private fun DocumentSnapshot.longOrZero(key: String): Long = longOrNull(key) ?: 0L

/**
 * `sources` with Firestore's `Timestamp`s flattened to `Date`, so [parseSources] — and the
 * unit tests around it — stay free of Firestore types.
 */
private fun DocumentSnapshot.sourcesMap(): Map<*, *>? {
    val raw = get("sources") as? Map<*, *> ?: return null
    return raw.mapValues { (_, entry) ->
        (entry as? Map<*, *>)?.mapValues { (_, value) ->
            if (value is Timestamp) value.toDate() else value
        } ?: entry
    }
}

/**
 * My own entry as this sync just wrote it, in place of whatever the re-read returned.
 * The re-read can legitimately serve a slightly older `sources.android` (Firestore's own
 * cache, or a server that has not caught up with the write we just made), and summing that
 * would publish a combined total lower than the one we just published for ourselves.
 */
private fun List<SourceTotals>.replacing(mine: SourceTotals): List<SourceTotals> =
    filterNot { it.source == mine.source } + mine

/**
 * Firestore reports a rules rejection as PERMISSION_DENIED and says nothing about which
 * clause failed, so this cannot distinguish "the row is gone" from a genuine access
 * problem. See [mergeOrRecreate] for why reading it as the former is the safe default.
 */
private fun isRulesRejection(error: Throwable): Boolean =
    (error as? FirebaseFirestoreException)?.code ==
        FirebaseFirestoreException.Code.PERMISSION_DENIED

/**
 * Field-by-field and untyped on purpose: a half-written doc must not crash the board.
 * [densityDpi] is only consulted for rows that carry no `*Um` at all — see [rankUm].
 */
private fun DocumentSnapshot.toEntry(densityDpi: Int): LeaderboardEntry {
    // A row whose key is last period's reads as zero for that period rather than dropping
    // off, so the fallback is not consulted for a stale period either.
    fun period(key: String, current: String, um: String, pixels: String): Long =
        if (getString(key) == current) rankUm(longOrNull(um), longOrZero(pixels), densityDpi)
        else 0L
    return LeaderboardEntry(
        uid = id,
        displayName = SocialRepository.entryName(this),
        photoUrl = SocialRepository.entryPhoto(this),
        weekUm = period("weekKey", weekKey(), "weekUm", "weekPixels"),
        monthUm = period("monthKey", monthKey(), "monthUm", "monthPixels"),
        totalUm = rankUm(longOrNull("totalUm"), longOrZero("totalPixels"), densityDpi),
    )
}
