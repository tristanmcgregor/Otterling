package au.com.tbmcgregor.bwparker.familyguard.focus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Fully on-device, no-network visual-similarity check used to approve/reject a habit's daily
 * proof photo against its stored reference photo (see [HabitProofRequirement.referencePhotoPath]).
 * Uses a difference hash (dHash): downscale to a small grayscale grid and record which way
 * brightness slopes between adjacent pixels, then compare two images by Hamming distance between
 * their hashes. This is a coarse "does this look like roughly the same scene" check --
 * composition, lighting, colors -- not identity or object recognition, and it isn't meant to be
 * adversarially secure against someone determined to fool it. It's meant to stop the laziest form
 * of cheating: submitting a random/unrelated photo instead of one of you actually doing the habit.
 */
object ImageMatcher {
    private const val GRID_SIZE = 9 // 9x8 samples -> 8x8 = 64 pairwise comparisons -> fits a Long

    /**
     * True if [candidateFile] is visually similar enough to [referenceFile] to approve.
     *
     * [thresholdBits] is the max allowed Hamming distance out of 64 bits: 0 = pixel-identical,
     * ~32 = statistically unrelated images. The default is deliberately lenient -- a proof photo
     * of "the same scene" taken at a different angle/time/lighting can easily differ by a couple
     * dozen bits, and the point is only to reject obviously-unrelated photos (a screenshot, a wall,
     * a random object), not to demand a near-duplicate of the reference. Lower it if it's letting
     * unrelated photos through; raise it if it's rejecting genuine ones.
     */
    fun isMatch(candidateFile: File, referenceFile: File, thresholdBits: Int = 30): Boolean {
        val candidate = decodeShrunk(candidateFile) ?: return false
        val reference = decodeShrunk(referenceFile) ?: return false
        return hammingDistance(dHash(candidate), dHash(reference)) <= thresholdBits
    }

    private fun decodeShrunk(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    /** Bit `i` is set if the pixel at position `i` is brighter than its right-hand neighbor in a
     * [GRID_SIZE] x (GRID_SIZE-1) grayscale grid -- robust to small brightness/exposure shifts
     * since it only cares about *relative* brightness between neighbors, not absolute values. */
    private fun dHash(bitmap: Bitmap): Long {
        val width = GRID_SIZE
        val height = GRID_SIZE - 1
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = grayscale(resized.getPixel(x, y))
                val right = grayscale(resized.getPixel(x + 1, y))
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    private fun grayscale(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
