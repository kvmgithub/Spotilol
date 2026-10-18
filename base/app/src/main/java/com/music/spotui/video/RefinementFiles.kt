package com.music.spotui.video

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Temporary audio owned by exactly one full-map computation. */
internal data class RefinementFiles(val reference: File, val candidate: File) : Closeable {
    override fun close() {
        reference.delete()
        candidate.delete()
    }

    companion object {
        fun retain(reference: File, candidate: File, directory: File): RefinementFiles? {
            val token = UUID.randomUUID().toString()
            val owned = RefinementFiles(File(directory, "mv_full_ref_$token"), File(directory, "mv_full_cand_$token"))
            if (!reference.renameTo(owned.reference) || !candidate.renameTo(owned.candidate)) {
                owned.close()
                return null
            }
            return owned
        }

        fun empty(directory: File): RefinementFiles {
            val token = UUID.randomUUID().toString()
            return RefinementFiles(File(directory, "mv_full_ref_$token"), File(directory, "mv_full_cand_$token"))
        }
    }
}
