package com.mdev64.simpletvlauncher

import android.app.Activity
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Remote-friendly wallpaper picker, drawn inside the activity (Android TV's own pickers are
 * either a "not supported" stub or, like the photo picker, can't be driven with a D-pad).
 *
 *   folders : "Built-in" (wallpapers shipped in res/raw) first, then every MediaStore image
 *             bucket, newest first, cover = its newest photo
 *   photos  : one folder's images, newest first
 *
 * Opens straight into [startBucket] (the folder the last wallpaper came from) when it still
 * exists, or into the only folder there is; BACK goes photos → folders → closed
 * ([handleBack]). The TV's own photos need READ_MEDIA_IMAGES (READ_EXTERNAL_STORAGE ≤ 32);
 * without it ([hasPhotoAccess] false) only the built-in folder shows.
 */
class ImagePickerOverlay(
    private val activity: Activity,
    private val root: FrameLayout,
    private val accent: Int,
    private val startBucket: String?,
    private val currentUri: String?,
    private val hasPhotoAccess: Boolean,
    /** Picked photo + the bucket it came from. */
    private val onPick: (Uri, String) -> Unit,
    private val onClosed: () -> Unit
) {
    private class Photo(val id: Long, val uri: Uri)
    private class Folder(val id: String, val name: String, val photos: List<Photo>)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val thumbs = object : LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val priorFocusability = ArrayList<Pair<ViewGroup, Int>>()

    private var folders: List<Folder> = emptyList()
    private var openFolder: Folder? = null
    private var lastFolderPos = 0
    private var closed = false

    private lateinit var scrim: FrameLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private val adapter = TileAdapter()

    private var tileWidth = 0
    private val thumbW get() = (tileWidth * TileStyler.APP_SCALE).toInt().coerceAtLeast(1)
    private val thumbH get() = (thumbW * 9 / 16).coerceAtLeast(1)

    val isShowing get() = !closed

    fun show() {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) {
                priorFocusability.add(child to child.descendantFocusability)
                child.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }
        scrim = FrameLayout(activity).apply {
            setBackgroundColor(0xFF0C0C13.toInt())
            isClickable = true
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(30), dp(30), dp(30), 0)
        }
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        title = text(26f, Color.WHITE, bold = true).apply { text = "Choose wallpaper" }
        subtitle = text(14f, 0xB3FFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) }
        left.addView(title)
        left.addView(subtitle)
        hint = text(11.5f, 0xA6FFFFFF.toInt()).apply { gravity = Gravity.END }
        header.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(hint)
        scrim.addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val width = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val margin = activity.resources.getDimensionPixelSize(R.dimen.tile_margin_h)
        tileWidth = (width - 2 * dp(16)) / COLUMNS - 2 * margin

        grid = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, COLUMNS)
            adapter = this@ImagePickerOverlay.adapter
            clipChildren = false
            clipToPadding = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(16), dp(12), dp(16), dp(16))
            itemAnimator = null
        }
        scrim.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(84)
            bottomMargin = dp(58) // footer hint
        })
        status = text(15f, 0xC7FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            text = "Looking for photos…"
        }
        scrim.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // How to get more pictures onto the TV (or, without access, how to grant it).
        val footer = text(12f, 0x99FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            text = if (hasPhotoAccess) {
                "Add your own: copy photos to the TV from a USB drive, a file-sharing app " +
                    "(e.g. LocalSend) or a file manager — they appear here as folders."
            } else {
                "To use your own photos, allow photo access in TV Settings › Apps › " +
                    "Simple TV Launcher › Permissions."
            }
        }
        scrim.addView(footer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            leftMargin = dp(48); rightMargin = dp(48); bottomMargin = dp(27)
        })
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Keep focus inside the overlay while the list loads.
        scrim.isFocusable = true
        scrim.requestFocus()

        worker.execute {
            val found = queryFolders()
            main.post { if (!closed) onLoaded(found) }
        }
    }

    /** BACK: photos → folders → close. Always consumes the key. */
    fun handleBack(): Boolean {
        if (openFolder != null && folders.size > 1) {
            showFolders(focusPos = lastFolderPos)
        } else {
            close()
        }
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        priorFocusability.forEach { (vg, mode) -> vg.descendantFocusability = mode }
        priorFocusability.clear()
        root.removeView(scrim)
        worker.shutdownNow()
        thumbs.evictAll()
        onClosed()
    }

    // ------------------------------------------------------------ data

    private fun queryFolders(): List<Folder> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val byBucket = LinkedHashMap<String, Pair<String, ArrayList<Photo>>>()
        try {
            activity.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val bucket = c.getString(bucketCol) ?: "?"
                    val name = c.getString(nameCol) ?: "Photos"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    byBucket.getOrPut(bucket) { name to ArrayList() }.second.add(Photo(id, uri))
                }
            }
        } catch (_: Exception) { }
        // Insertion order = order of each bucket's newest photo, i.e. most recent first.
        return listOf(builtIn()) + byBucket.map { (id, v) -> Folder(id, v.first, v.second) }
    }

    /**
     * Wallpapers shipped with the launcher (res/raw/wallpaper_builtin_*.webp, 1600×900) so a
     * fresh TV has something to pick. android.resource:// URIs go through the same
     * ContentResolver path as photos, so WallpaperStore needs no special case.
     * Photos: Benjamin Voros, John Towner, Jonatan Pie (Unsplash); Trung Nguyen,
     * Kienvirak (Pexels) — free-to-use licences.
     */
    private fun builtIn(): Folder {
        val pkg = activity.packageName
        // By name (…/raw/wallpaper_builtin_1), not number: resource ids can change between
        // builds, and the URI is stored as the wallpaper.
        val photos = BUILT_IN.mapIndexed { i, res ->
            val name = activity.resources.getResourceEntryName(res)
            Photo(-1L - i, Uri.parse("android.resource://$pkg/raw/$name"))
        }
        return Folder(BUILT_IN_BUCKET, "Built-in", photos)
    }

    private fun onLoaded(found: List<Folder>) {
        folders = found
        status.visibility = View.GONE
        val start = found.firstOrNull { it.id == startBucket } ?: found.singleOrNull()
        if (start != null) {
            lastFolderPos = found.indexOf(start).coerceAtLeast(0)
            showPhotos(start)
        } else {
            showFolders(focusPos = 0)
        }
    }

    // ------------------------------------------------------------ screens

    private fun showFolders(focusPos: Int) {
        openFolder = null
        subtitle.text = "${folders.size} folders"
        hint.text = "OK to open a folder  ·  BACK to cancel"
        adapter.submit(folders.map { Tile(it.photos.first(), it.name, "${it.photos.size}", folder = it) })
        focus(focusPos)
    }

    private fun showPhotos(folder: Folder) {
        openFolder = folder
        subtitle.text = "${folder.name}  ·  ${folder.photos.size} photos"
        hint.text = if (folders.size > 1) "OK to set as wallpaper  ·  BACK for all folders"
        else "OK to set as wallpaper  ·  BACK to cancel"
        adapter.submit(folder.photos.map { Tile(it, null, null, folder = null) })
        val current = folder.photos.indexOfFirst { it.uri.toString() == currentUri }
        focus(current.coerceAtLeast(0))
    }

    private fun focus(pos: Int) {
        grid.scrollToPosition(pos)
        grid.post {
            val vh = grid.findViewHolderForAdapterPosition(pos) as? TileVH
            (vh?.tile ?: grid.getChildAt(0)?.findViewById(R.id.tile))?.requestFocus()
        }
    }

    private fun onTileClicked(tile: Tile) {
        val folder = tile.folder
        if (folder != null) {
            lastFolderPos = folders.indexOf(folder).coerceAtLeast(0)
            showPhotos(folder)
            return
        }
        val bucket = openFolder?.id ?: return
        close()
        onPick(tile.photo.uri, bucket)
    }

    // ------------------------------------------------------------ grid

    private class Tile(val photo: Photo, val label: String?, val count: String?, val folder: Folder?)

    private inner class TileVH(root: View) : RecyclerView.ViewHolder(root) {
        val tile: View = root.findViewById(R.id.tile)
        val art: ImageView = root.findViewById<ImageView>(R.id.tile_art).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val label: TextView = TextView(root.context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(12), dp(22), dp(12), dp(9))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xD9000000.toInt(), 0x00000000)
            )
        }
        var boundId = -1L

        init {
            (tile as ViewGroup).addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            TileStyler.attach(tile, accent, TileStyler.APP_SCALE)
        }
    }

    private inner class TileAdapter : RecyclerView.Adapter<TileVH>() {
        private var items: List<Tile> = emptyList()

        fun submit(next: List<Tile>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TileVH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

        override fun onBindViewHolder(holder: TileVH, position: Int) {
            val t = items[position]
            holder.tile.setOnClickListener { onTileClicked(t) }
            holder.tile.contentDescription = t.label ?: "Photo"
            if (t.label != null) {
                holder.label.visibility = View.VISIBLE
                holder.label.text = "${t.label}   ${t.count}"
            } else {
                holder.label.visibility = View.GONE
            }
            val id = t.photo.id
            holder.boundId = id
            val cached = thumbs.get(id)
            if (cached != null) {
                holder.art.setImageBitmap(cached)
                return
            }
            holder.art.setImageDrawable(null)
            if (worker.isShutdown) return
            worker.execute {
                val bmp = loadThumb(t.photo.uri) ?: return@execute
                thumbs.put(id, bmp)
                main.post { if (holder.boundId == id) holder.art.setImageBitmap(bmp) }
            }
        }
    }

    private fun loadThumb(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            activity.contentResolver.loadThumbnail(uri, Size(thumbW, thumbH), null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= thumbW && bounds.outHeight / (sample * 2) >= thumbH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ helpers

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    private companion object {
        const val COLUMNS = 5
        const val BUILT_IN_BUCKET = "builtin"
        val BUILT_IN = intArrayOf(
            R.raw.wallpaper_builtin_1, R.raw.wallpaper_builtin_2, R.raw.wallpaper_builtin_3,
            R.raw.wallpaper_builtin_4, R.raw.wallpaper_builtin_5
        )
    }
}
