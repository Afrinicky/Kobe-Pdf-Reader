package com.kobe.reader.pdf.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of rendered pages, sized as a fraction of the app's heap.
 *
 * Rendering a page costs tens of milliseconds; scrolling back one page and
 * re-rendering is the difference between smooth and janky on a mid-range phone.
 * The cache is deliberately memory-only - a disk cache of page images would put
 * copies of the user's documents somewhere they didn't ask for, which is exactly
 * what the privacy promise rules out.
 */
@Singleton
class PageBitmapCache @Inject constructor(
    @param:ApplicationContext context: Context,
) {

    private val cache: LruCache<Key, Bitmap> = object : LruCache<Key, Bitmap>(budgetKb(context)) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount / 1024
    }

    operator fun get(key: Key): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    fun put(key: Key, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /** Drops everything for one document, e.g. after its pages were reordered. */
    fun evictDocument(documentKey: String) {
        cache.snapshot().keys
            .filter { it.documentKey == documentKey }
            .forEach { cache.remove(it) }
    }

    fun clear() = cache.evictAll()

    /**
     * Called from `onTrimMemory`. Halving beats clearing: the user is usually
     * still on the same page, and a full clear guarantees a visible re-render.
     */
    fun trim(aggressive: Boolean) {
        if (aggressive) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
    }

    /**
     * @param documentKey stable per document (its URI)
     * @param pageIndex 0-based
     * @param widthPx bitmaps rendered at different widths are different entries;
     *   a thumbnail must never be blown up into the reader
     */
    data class Key(val documentKey: String, val pageIndex: Int, val widthPx: Int)

    private companion object {
        /**
         * Fraction of the per-app heap limit given over to page bitmaps. An
         * eighth leaves plenty of room for PdfBox to hold a document while a
         * tool runs, which is when memory pressure actually bites.
         */
        const val HEAP_FRACTION = 8

        const val MIN_BUDGET_KB = 8 * 1024
        const val MAX_BUDGET_KB = 96 * 1024

        fun budgetKb(context: Context): Int {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val heapMb = activityManager?.memoryClass ?: 64
            return ((heapMb * 1024) / HEAP_FRACTION).coerceIn(MIN_BUDGET_KB, MAX_BUDGET_KB)
        }
    }
}
