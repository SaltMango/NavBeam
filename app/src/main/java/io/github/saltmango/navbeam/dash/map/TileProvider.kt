package io.github.saltmango.navbeam.dash.map

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import io.github.saltmango.navbeam.dash.nav.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class PrefetchProgress(
    val isDownloading: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0
) {
    val percentage: Int get() = if (total > 0) ((completed.toFloat() / total) * 100).coerceIn(0f, 100f).toInt() else 100
}

/**
 * OSM raster tile provider with memory + disk cache.
 *
 * Tiles are darkened ONCE at load (invert + desaturate + dim) and the dark bitmap
 * is cached, so the render loop never re-runs the colour matrix per frame — a key
 * power win at 4 fps.
 *
 * While riding, the process is bound to the dash WiFi (no internet), so tiles
 * must come from cache — [prefetch]/[prefetchRoute] populate it while internet is
 * still reachable. Cache misses fetch through whichever network has connectivity
 * (cellular when bound to the dash WiFi), rate-limited to avoid hot loops.
 */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    private val _prefetchProgress = MutableStateFlow(PrefetchProgress())
    val prefetchProgress: StateFlow<PrefetchProgress> = _prefetchProgress.asStateFlow()
    companion object {
        private const val TAG = "TileProvider"
        // Actual Google Maps roadmap tiles (the colours/contrast the rider wants —
        // distinct building fills, clear white roads). This is Google's tile endpoint;
        // it's used here for a PERSONAL, single-user, non-distributed build. The
        // compliant alternative for any public release is Google's Map Tiles API
        // (session token + the existing MAPS_API_KEY). Format args: (z, x, y).
        //
        // Standard 256 px tiles with live traffic layer enabled.
        private const val URL_TEMPLATE =
            "https://mt1.google.com/vt/lyrs=m,traffic&hl=en&z=%d&x=%d&y=%d"
        // Browser-like UA so the tile endpoint serves us normally.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 60L // be gentle on OSM + the radio
        private const val TILE_MAX_AGE_MS = 5 * 60 * 1000L // 5 minutes for live traffic

        /** ~1/5 of the app heap for the tile cache, clamped to a sane 48–128 MB. */
        private fun memoryCacheBytes(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val heapBytes = am.memoryClass.toLong() * 1024L * 1024L
            return (heapBytes / 5L).coerceIn(48L * 1024 * 1024, 128L * 1024 * 1024).toInt()
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Cache dir per tile source so a source switch doesn't serve stale tiles. Bumped namespace so
    // the lighter 256 px tiles don't collide with any 512 px tiles a prior build cached.
    private val diskDir = File(context.cacheDir, "tiles_gmaps_traffic_v1").apply { mkdirs() }
    // Byte-budgeted (not count-based): adapts to tile size and OEM heap, keeping the current zoom
    // PLUS a neighbouring level resident for the renderer's scaled-tile fallback.
    private val memory = object : LruCache<String, Bitmap>(memoryCacheBytes(context)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastFetchAt = 0L

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "$z/$xw/$y"

        val memBmp = memory.get(key)
        val file = diskFile(key)
        val isStale = file.exists() && (System.currentTimeMillis() - file.lastModified() > TILE_MAX_AGE_MS)

        // If it's in memory and not stale, use it immediately.
        if (memBmp != null && !isStale) return memBmp

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val hasNet = internetNetwork() != null
                    // If we have internet, prioritize a fresh fetch if the map is stale or missing.
                    val raw = if (hasNet && (isStale || !file.exists())) {
                        fetch(z, xw, y, key)
                    } else {
                        loadDisk(key) ?: if (hasNet) fetch(z, xw, y, key) else null
                    }
                    if (raw != null) memory.put(key, raw)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        // Return whatever is currently on disk/memory (even if stale) while the fresh one fetches.
        return memBmp ?: loadDisk(key)
    }

    /**
     * Memory-cache-only peek — render-thread safe and never triggers a load. Used by the
     * renderer to substitute a cached neighbouring-zoom tile while the exact one loads.
     */
    fun getCached(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        return memory.get("$z/$xw/$y")
    }

    /** Warm a single zoom level around a point right now (e.g. right after the rider zooms). */
    fun prefetchZoom(lat: Double, lng: Double, z: Int, radius: Int = 2) {
        if (z < 0) return
        scope.launch(Dispatchers.IO) { prefetchAround(lat, lng, z, radius) }
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // 11..20 so the rider's vicinity has tiles at every zoom level offline
            // (default nav zoom is now 19, max 20).
            for (z in 11..20) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            Log.i(TAG, "Prefetch (point) done — ~$count tiles ensured")
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            val hasNet = internetNetwork() != null
            if (!hasNet) {
                _prefetchProgress.value = PrefetchProgress(isDownloading = false, completed = 0, total = 0)
                return@launch
            }

            val requiredTiles = LinkedHashSet<Triple<Int, Int, Int>>()
            for (z in 12..16) {
                val step = if (z >= 15) 1 else 3
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val x = cx + dx
                        val y = cy + dy
                        val max = 1 shl z
                        if (y in 0 until max) {
                            val xw = ((x % max) + max) % max
                            requiredTiles.add(Triple(z, xw, y))
                        }
                    }
                    i += step
                    if (requiredTiles.size >= MAX_PREFETCH_TILES) break
                }
                if (requiredTiles.size >= MAX_PREFETCH_TILES) break
            }

            val total = requiredTiles.size
            if (total == 0) {
                _prefetchProgress.value = PrefetchProgress(isDownloading = false, completed = 0, total = 0)
                return@launch
            }

            var completed = 0
            val toFetch = ArrayList<Triple<Int, Int, Int>>()
            for (tile in requiredTiles) {
                val (z, x, y) = tile
                val key = "$z/$x/$y"
                val f = diskFile(key)
                val isStale = f.exists() && (System.currentTimeMillis() - f.lastModified() > TILE_MAX_AGE_MS)
                if (f.exists() && !isStale) {
                    completed++
                } else {
                    toFetch.add(tile)
                }
            }

            _prefetchProgress.value = PrefetchProgress(
                isDownloading = toFetch.isNotEmpty(),
                completed = completed,
                total = total
            )

            var fetchedCount = 0
            for (tile in toFetch) {
                val (z, x, y) = tile
                val key = "$z/$x/$y"
                fetchKey(z, x, y, key)
                completed++
                fetchedCount++
                _prefetchProgress.value = PrefetchProgress(
                    isDownloading = true,
                    completed = completed,
                    total = total
                )
            }

            _prefetchProgress.value = PrefetchProgress(
                isDownloading = false,
                completed = total,
                total = total
            )
            Log.i(TAG, "Prefetch (route) done — $total tiles ensured ($fetchedCount newly fetched)")
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? = fetchKey(z, x, y, key)

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        val net = internetNetwork()
        return try {
            val url = URL(URL_TEMPLATE.format(z, x, ((y % max) + max) % max))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Tile $key HTTP ${conn.responseCode}")
                conn.disconnect(); return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            // Only cache a tile that actually DECODES. Writing a throttle/error body or a
            // truncated read to disk would poison the cache: the file would then exist (so
            // prefetch skips it) but never decode, leaving a permanently blank tile offline.
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                Log.w(TAG, "Tile $key decoded null (${bytes.size}B) — not caching")
                return null
            }
            diskFile(key).writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key fetch failed: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
