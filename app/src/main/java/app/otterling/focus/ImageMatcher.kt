package app.otterling.focus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.File

/**
 * On-device, no-network visual-similarity check used to approve/reject a habit's daily proof photo
 * against its stored reference photo(s). Primary path uses a MobileNet-V3 image embedder
 * (MediaPipe, model bundled in `assets/mobilenet_embedder.tflite`): it turns each image into a
 * semantic feature vector and compares two images by cosine similarity. Unlike the old difference
 * hash, this captures *what's in the scene* rather than just its coarse brightness gradient, so it
 * both rejects unrelated photos and tolerates the same scene at a different angle/lighting far
 * better. If the embedder can't be initialised for any reason, it falls back to the legacy dHash so
 * proof never hard-breaks.
 *
 * This is anti-laziness, not adversarially secure: someone determined can still photograph the
 * reference itself. It exists to stop submitting a random/unrelated photo instead of one of you
 * actually doing the habit.
 */
object ImageMatcher {
    private const val TAG = "ImageMatcher"
    private const val MODEL_ASSET = "mobilenet_embedder.tflite"

    /** How strict the match must be. Higher [minCosine] = stricter (fewer false accepts, more
     * false rejects). [fallbackMaxBits] is the equivalent for the dHash fallback (lower = stricter,
     * out of 64). */
    enum class Sensitivity(val minCosine: Double, val fallbackMaxBits: Int) {
        LENIENT(0.42, 26),
        NORMAL(0.55, 20),
        STRICT(0.68, 14),
    }

    private val embedderLock = Any()
    @Volatile private var embedder: ImageEmbedder? = null
    @Volatile private var embedderInitFailed = false

    /** Best-effort eager init so the first real proof check isn't slowed by model load. Safe to
     * call from a background thread; no-op if already initialised or previously failed. */
    fun warmUp(context: Context) {
        ensureEmbedder(context)
    }

    private fun ensureEmbedder(context: Context): ImageEmbedder? {
        embedder?.let { return it }
        if (embedderInitFailed) return null
        synchronized(embedderLock) {
            embedder?.let { return it }
            if (embedderInitFailed) return null
            return try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
                val options = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build()
                ImageEmbedder.createFromOptions(context.applicationContext, options).also {
                    embedder = it
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Image embedder init failed; falling back to dHash", t)
                embedderInitFailed = true
                null
            }
        }
    }

    /**
     * True if [candidateFile] visually matches any of [referenceFiles] closely enough per
     * [sensitivity]. Returns false if there are no readable references or the candidate can't be
     * decoded. Call from a background thread -- decoding + embedding are blocking.
     */
    fun isMatch(
        context: Context,
        candidateFile: File,
        referenceFiles: List<File>,
        sensitivity: Sensitivity = Sensitivity.NORMAL,
    ): Boolean {
        val readable = referenceFiles.filter { it.exists() && it.length() > 0 }
        if (readable.isEmpty() || !candidateFile.exists()) return false

        val emb = ensureEmbedder(context)
        if (emb != null) {
            val best = bestCosine(emb, candidateFile, readable)
            if (best != null) {
                Log.d(TAG, "best cosine=$best threshold=${sensitivity.minCosine} for ${candidateFile.name}")
                return best >= sensitivity.minCosine
            }
            // Embedding computation failed for this pair -- fall through to dHash.
        }
        return matchViaDHash(candidateFile, readable, sensitivity.fallbackMaxBits)
    }

    /** Highest cosine similarity of [candidateFile] against any of [references], or null if none
     * could be embedded. */
    private fun bestCosine(emb: ImageEmbedder, candidateFile: File, references: List<File>): Double? {
        val candidateEmbedding = embed(emb, candidateFile) ?: return null
        var best = Double.NEGATIVE_INFINITY
        var found = false
        for (ref in references) {
            val refEmbedding = embed(emb, ref) ?: continue
            val cosine = runCatching {
                ImageEmbedder.cosineSimilarity(candidateEmbedding, refEmbedding)
            }.getOrNull() ?: continue
            best = maxOf(best, cosine)
            found = true
        }
        return if (found) best else null
    }

    private fun embed(emb: ImageEmbedder, file: File): Embedding? {
        val bitmap = decodeShrunk(file, maxDim = 512) ?: return null
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = synchronized(embedderLock) { emb.embed(mpImage) }
            result.embeddingResult().embeddings().firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "embed failed for ${file.name}", t)
            null
        }
    }

    // ---- Legacy dHash fallback ---------------------------------------------------------------

    private const val GRID_SIZE = 9

    private fun matchViaDHash(candidateFile: File, references: List<File>, maxBits: Int): Boolean {
        val candidate = decodeShrunk(candidateFile, maxDim = 512)?.let(::dHash) ?: return false
        return references.any { ref ->
            val refHash = decodeShrunk(ref, maxDim = 512)?.let(::dHash) ?: return@any false
            hammingDistance(candidate, refHash) <= maxBits
        }
    }

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

    // ---- Shared helpers ----------------------------------------------------------------------

    private fun decodeShrunk(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (largest > maxDim) {
            while (largest / (sample * 2) >= maxDim) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }
}
