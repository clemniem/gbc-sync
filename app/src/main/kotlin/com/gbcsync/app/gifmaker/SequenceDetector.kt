package com.gbcsync.app.gifmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImageSequence(
    val files: List<File>,
    val firstFrameName: String,
    val lastFrameName: String,
)

data class LoopResult(
    /** Index in the original files list where the loop should end (inclusive). */
    val endIndex: Int,
    /** How well the end frame matches the first frame (0 = identical, 1 = completely different). */
    val diffRatio: Float,
) {
    /** Match quality as a percentage (100% = perfect match). */
    val matchPercent: Int get() = ((1f - diffRatio) * 100).toInt()
}

class SequenceDetector(
    private val diffThreshold: Float = 0.35f,
    private val minSequenceLength: Int = 3,
) {
    /**
     * Detect video sequences in a folder of PNG images.
     * Groups consecutive frames that differ by less than [diffThreshold] of their pixels.
     */
    suspend fun detectSequences(folder: File): List<ImageSequence> = withContext(Dispatchers.IO) {
        val pngFiles = folder.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return@withContext emptyList()

        if (pngFiles.size < minSequenceLength) return@withContext emptyList()

        // Compute diff ratios between consecutive pairs
        val similar = BooleanArray(pngFiles.size - 1)
        var prevBitmap: Bitmap? = null

        for (i in pngFiles.indices) {
            val bitmap = BitmapFactory.decodeFile(pngFiles[i].absolutePath) ?: continue
            if (prevBitmap != null) {
                val diff = Companion.computeDiffRatio(prevBitmap, bitmap)
                similar[i - 1] = diff < diffThreshold
            }
            prevBitmap?.recycle()
            prevBitmap = bitmap
        }
        prevBitmap?.recycle()

        // Group consecutive similar pairs into sequences
        val sequences = mutableListOf<ImageSequence>()
        var start = 0
        while (start < similar.size) {
            if (similar[start]) {
                var end = start
                while (end < similar.size && similar[end]) end++
                // Sequence spans indices start..end (inclusive), which is end-start+1 frames
                val frameCount = end - start + 1
                if (frameCount >= minSequenceLength) {
                    val files = pngFiles.subList(start, end + 1)
                    sequences.add(
                        ImageSequence(
                            files = files,
                            firstFrameName = files.first().nameWithoutExtension,
                            lastFrameName = files.last().nameWithoutExtension,
                        ),
                    )
                }
                start = end + 1
            } else {
                start++
            }
        }

        sequences
    }

    /**
     * Find the best loop endpoint: the frame whose content best matches the first active frame.
     * Only considers frames past a minimum loop length (30% of active frames or 5, whichever is larger).
     */
    suspend fun findLoopPoint(
        files: List<File>,
        excludedFrames: Set<Int>,
    ): LoopResult? = withContext(Dispatchers.IO) {
        val activeIndices = files.indices.filter { it !in excludedFrames }
        if (activeIndices.size < 5) return@withContext null

        val firstBitmap = BitmapFactory.decodeFile(files[activeIndices.first()].absolutePath)
            ?: return@withContext null
        val firstPixels = IntArray(firstBitmap.width * firstBitmap.height)
        firstBitmap.getPixels(firstPixels, 0, firstBitmap.width, 0, 0, firstBitmap.width, firstBitmap.height)

        val minLoopLen = maxOf(5, (activeIndices.size * 0.3f).toInt())
        var bestIndex = -1
        var bestDiff = 1f

        for (pos in minLoopLen until activeIndices.size) {
            val idx = activeIndices[pos]
            val bitmap = BitmapFactory.decodeFile(files[idx].absolutePath) ?: continue
            val diff = computeDiffRatio(firstPixels, bitmap)
            bitmap.recycle()
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = idx
            }
        }

        firstBitmap.recycle()
        if (bestIndex < 0) null else LoopResult(bestIndex, bestDiff)
    }

    companion object {
        fun computeDiffRatio(a: Bitmap, b: Bitmap): Float {
            if (a.width != b.width || a.height != b.height) return 1f

            val size = a.width * a.height
            val pixelsA = IntArray(size)
            val pixelsB = IntArray(size)
            a.getPixels(pixelsA, 0, a.width, 0, 0, a.width, a.height)
            b.getPixels(pixelsB, 0, b.width, 0, 0, b.width, b.height)

            var diffCount = 0
            for (i in 0 until size) {
                if (pixelsA[i] != pixelsB[i]) diffCount++
            }
            return diffCount.toFloat() / size
        }

        /** Compare pre-extracted pixels against a bitmap. */
        fun computeDiffRatio(pixelsA: IntArray, b: Bitmap): Float {
            val size = b.width * b.height
            if (pixelsA.size != size) return 1f

            val pixelsB = IntArray(size)
            b.getPixels(pixelsB, 0, b.width, 0, 0, b.width, b.height)

            var diffCount = 0
            for (i in 0 until size) {
                if (pixelsA[i] != pixelsB[i]) diffCount++
            }
            return diffCount.toFloat() / size
        }
    }
}
