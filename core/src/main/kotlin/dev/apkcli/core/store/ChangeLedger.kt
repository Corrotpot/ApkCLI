package dev.apkcli.core.store

import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.util.int
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the agent changed in a session, per file, and the way back.
 *
 * Built entirely on the before/after snapshots that every write, edit, patch
 * and delete already records (see `FsTools.snapshot`). Nothing new is stored:
 * a "change set" is the unreverted snapshots after a point in time, grouped by
 * path, and reverting one means restoring the *earliest* snapshot's "before"
 * blob — the file as it was before the agent first touched it in that window —
 * then marking every snapshot in the group reverted.
 *
 * Restoring the earliest state rather than undoing snapshot by snapshot is the
 * point: three edits to one file are one change from the user's point of view,
 * and "revert" should mean "put it back how it was", not "undo the last edit".
 *
 * Edits to files above the snapshot size cap are not recorded and so do not
 * appear here; directory deletes and moves are not snapshotted either. That is
 * the same limit `revert_edit` has, and the UI says so rather than implying
 * completeness.
 */
class ChangeLedger(
    private val store: ConversationStore,
    private val layout: EnvLayout,
) {

    data class FileChange(
        /** Absolute path. */
        val path: String,
        val kind: Kind,
        /** Every unreverted snapshot of this path in the window, newest first. */
        val snapshotIds: List<String>,
        /** The file before the agent's first edit in the window; null when it did not exist. */
        val originalBlobId: String?,
        val firstChangedAt: Long,
        val lastChangedAt: Long,
    ) {
        enum class Kind { ADDED, MODIFIED, DELETED }

        /** How many separate edits went into this change. */
        val editCount: Int get() = snapshotIds.size
    }

    data class FileDiff(
        val unified: String,
        val added: Int,
        val removed: Int,
        /** Binary content or too large to diff on the device; [unified] is empty. */
        val skipped: Boolean = false,
    )

    sealed interface RevertResult {
        data object Reverted : RevertResult

        /** Nothing was written; [reason] is for the user. */
        data class Failed(val reason: String) : RevertResult
    }

    /**
     * Unreverted changes made after [sinceMillis], one entry per file, most
     * recently changed first.
     *
     * A file the agent created and then deleted again inside the window nets
     * out to nothing and is left out.
     */
    suspend fun pending(sessionId: String, sinceMillis: Long = 0): List<FileChange> =
        withContext(Dispatchers.IO) {
            val snapshots = store.snapshots(sessionId)
                .filter { !it.reverted && it.createdAt > sinceMillis && it.path.isNotBlank() }

            // The store lists newest first. Grouping keeps that order inside
            // each group, so the last element is the earliest edit.
            snapshots
                .groupBy { it.path }
                .mapNotNull { (path, group) ->
                    val earliest = group.last()
                    val exists = File(path).exists()
                    val kind = when {
                        earliest.beforeBlobId == null && exists -> FileChange.Kind.ADDED
                        earliest.beforeBlobId == null -> return@mapNotNull null
                        exists -> FileChange.Kind.MODIFIED
                        else -> FileChange.Kind.DELETED
                    }
                    FileChange(
                        path = path,
                        kind = kind,
                        snapshotIds = group.map { it.id },
                        originalBlobId = earliest.beforeBlobId,
                        firstChangedAt = earliest.createdAt,
                        lastChangedAt = group.first().createdAt,
                    )
                }
                .sortedByDescending { it.lastChangedAt }
        }

    /**
     * What restoring a checkpoint puts back: every file the agent changed at
     * or after [sinceMillis], to how it was before the first of those changes.
     *
     * Unlike [pending], reverted and accepted snapshots count. A checkpoint
     * means "the files as they were then", and a file the user reverted and
     * the agent then edited again was, at the checkpoint, what its *first*
     * snapshot since remembers — not what its newest unreverted one does.
     * Files already in that state are left out, so the number the
     * confirmation shows is the number that will change.
     *
     * Restore each with [revert]; the snapshots go with the history they
     * belonged to (ConversationStore.rewind).
     */
    suspend fun sinceCheckpoint(sessionId: String, sinceMillis: Long): List<FileChange> =
        withContext(Dispatchers.IO) {
            store.snapshots(sessionId)
                .filter { it.createdAt >= sinceMillis && it.path.isNotBlank() }
                .groupBy { it.path }
                .mapNotNull { (path, group) ->
                    val earliest = group.last()
                    val file = File(path)
                    val original = earliest.beforeBlobId
                    val kind = when {
                        original == null && file.exists() -> FileChange.Kind.ADDED
                        original == null -> return@mapNotNull null
                        !file.exists() -> FileChange.Kind.DELETED
                        holds(file, original) -> return@mapNotNull null
                        else -> FileChange.Kind.MODIFIED
                    }
                    FileChange(
                        path = path,
                        kind = kind,
                        snapshotIds = group.map { it.id },
                        originalBlobId = original,
                        firstChangedAt = earliest.createdAt,
                        lastChangedAt = group.first().createdAt,
                    )
                }
                .sortedByDescending { it.lastChangedAt }
        }

    /** The file on disk is exactly [blobId]'s text. Unreadable either way counts as different. */
    private suspend fun holds(file: File, blobId: String): Boolean = runCatching {
        if (file.length() > MAX_DIFF_BYTES) return@runCatching false
        file.readText() == readWhole(blobId)
    }.getOrDefault(false)

    /**
     * Unified diff from the original to what is on disk now.
     *
     * Against the disk rather than the last snapshot's "after" blob, so edits
     * the user made in the editor since are part of what they review — and
     * part of what a revert would discard.
     */
    suspend fun diff(change: FileChange, displayPath: String): FileDiff = withContext(Dispatchers.IO) {
        val original = when (val blob = change.originalBlobId) {
            null -> ""
            else -> runCatching { readWhole(blob) }.getOrNull()
                ?: return@withContext FileDiff("", 0, 0, skipped = true)
        }
        val file = File(change.path)
        val current = when {
            !file.exists() -> ""
            file.length() > MAX_DIFF_BYTES -> return@withContext FileDiff("", 0, 0, skipped = true)
            else -> runCatching { file.readText() }.getOrNull()
                ?: return@withContext FileDiff("", 0, 0, skipped = true)
        }
        if (original.length > MAX_DIFF_BYTES || original.indexOf('\u0000') >= 0 || current.indexOf('\u0000') >= 0) {
            return@withContext FileDiff("", 0, 0, skipped = true)
        }

        runCatching {
            val unified = NativeCore.diff(
                "unified",
                jsonOf("path" to displayPath, "old" to original, "new" to current, "context" to 3),
            ).str("diff", "")
            val stats = NativeCore.diff("stats", jsonOf("old" to original, "new" to current))
            FileDiff(unified, stats.int("added"), stats.int("removed"))
        }.getOrElse { FileDiff("", 0, 0, skipped = true) }
    }

    /**
     * Put the file back the way it was before the agent's first edit in the
     * change, and mark every snapshot in it reverted.
     *
     * The blob is read completely before anything is written, so a pruned or
     * unreadable snapshot fails the revert instead of truncating the file.
     */
    suspend fun revert(change: FileChange): RevertResult = withContext(Dispatchers.IO) {
        val file = File(change.path)
        if (!layout.isInsideSandbox(file)) {
            return@withContext RevertResult.Failed("${change.path} is outside the sandbox")
        }

        val outcome = runCatching {
            when (val blob = change.originalBlobId) {
                null -> {
                    // Did not exist before the agent created it.
                    if (file.exists() && !file.delete()) error("could not delete ${file.name}")
                }
                else -> {
                    val original = readWhole(blob)
                    file.parentFile?.mkdirs()
                    file.writeText(original)
                }
            }
        }
        outcome.exceptionOrNull()?.let { error ->
            return@withContext RevertResult.Failed(error.message ?: error.javaClass.simpleName)
        }

        // The file is restored at this point. A failure to mark a snapshot only
        // means the change might be listed again; it must not report the
        // revert itself as failed.
        change.snapshotIds.forEach { id -> runCatching { store.markReverted(id) } }
        RevertResult.Reverted
    }

    private suspend fun readWhole(blobId: String): String {
        val page = store.readBlob(blobId, 0, Int.MAX_VALUE / 4)
        check(page.eof) { "snapshot $blobId could not be read in full" }
        return page.text
    }

    private companion object {
        /** Twice the snapshot cap: anything larger was never snapshotted anyway. */
        const val MAX_DIFF_BYTES = 1024L * 1024
    }
}
