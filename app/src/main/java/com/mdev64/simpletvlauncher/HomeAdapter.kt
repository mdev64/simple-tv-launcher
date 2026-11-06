package com.mdev64.simpletvlauncher

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executor

/** Pin capacity; Prefs reads it from here. The carousel home has no practical limit. */
object DockAdapter {
    const val DOCK_SIZE = Int.MAX_VALUE
}

/**
 * The dashboard content pane: one grid holding
 *   [Favourites header] [favourite tiles, 3 per row] [All apps header] [app tiles, 4 per row]
 *
 * Mixed tile sizes come from a 12-column [GridLayoutManager] ([SPAN] / [spanLookup]).
 */
class HomeAdapter(
    private val artwork: ArtworkLoader,
    private val executor: Executor,
    private val listener: Listener,
    /** Columns for non-favourite tiles; must divide [SPAN]. */
    val appColumns: Int = 4
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onAppClick(item: AppItem)
        fun onAppLongPress(item: AppItem, favourite: Boolean)
    }

    sealed class Row {
        abstract val id: Long
        data class Header(val title: String, val count: String, val hint: String) : Row() {
            // Offset keeps header ids clear of the 32-bit tile ids below.
            override val id: Long = (1L shl 40) + title.hashCode()
        }
        data class Tile(
            val app: AppItem,
            val favourite: Boolean,
            val index: Int,
            val sectionSize: Int
        ) : Row() {
            // Pinned apps are not repeated under "All apps", so the key alone is unique.
            override val id: Long = app.key.hashCode().toLong()
        }
    }

    private val rows = ArrayList<Row>()

    var accent: Int = Color.WHITE

    /** Unscaled tile widths in px for each section; set by the activity from the pane width. */
    var favouriteTileWidth = 0
    var appTileWidth = 0

    init {
        setHasStableIds(true)
    }

    class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.section_title)
        val count: TextView = root.findViewById(R.id.section_count)
        val hint: TextView = root.findViewById(R.id.section_hint)
    }

    class TileVH(root: View) : RecyclerView.ViewHolder(root) {
        val tile: View = root.findViewById(R.id.tile)
        val art: ImageView = root.findViewById(R.id.tile_art)
        var appliedAccent: Int = 0
        var appliedFavourite: Boolean? = null
    }

    fun submit(next: List<Row>) {
        if (rows == next) return
        rows.clear()
        rows.addAll(next)
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): Row? = rows.getOrNull(position)

    fun tileAt(position: Int): Row.Tile? = rows.getOrNull(position) as? Row.Tile

    fun positionOf(key: String, favourite: Boolean? = null): Int = rows.indexOfFirst {
        it is Row.Tile && it.app.key == key && (favourite == null || it.favourite == favourite)
    }

    fun firstTilePosition(): Int = rows.indexOfFirst { it is Row.Tile }

    val spanLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int = when (val r = rows.getOrNull(position)) {
            is Row.Tile -> if (r.favourite) SPAN / FAVOURITE_COLUMNS else SPAN / appColumns
            else -> SPAN
        }
    }.apply {
        isSpanIndexCacheEnabled = false
    }

    override fun getItemId(position: Int): Long = rows[position].id

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_section, parent, false))
        } else {
            TileVH(inflater.inflate(R.layout.item_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).apply {
                title.text = row.title
                count.text = row.count
                hint.text = row.hint
            }
            is Row.Tile -> bindTile(holder as TileVH, row)
        }
    }

    private fun bindTile(holder: TileVH, row: Row.Tile) {
        val item = row.app
        if (holder.appliedAccent != accent || holder.appliedFavourite != row.favourite) {
            TileStyler.attach(
                holder.tile, accent,
                if (row.favourite) TileStyler.FAVOURITE_SCALE else TileStyler.APP_SCALE
            )
            holder.appliedAccent = accent
            holder.appliedFavourite = row.favourite
        }
        holder.tile.contentDescription = item.label
        holder.tile.setOnClickListener { listener.onAppClick(item) }
        holder.tile.setOnLongClickListener {
            listener.onAppLongPress(item, row.favourite)
            true
        }

        val (w, h) = artSize(row.favourite)
        if (w <= 0) {
            holder.art.setImageDrawable(null)
            return
        }
        val cached = artwork.cached("$w x $h ${item.key}")
        if (cached != null) {
            holder.art.setImageBitmap(cached)
            return
        }
        holder.art.setImageDrawable(null)
        executor.execute {
            val bmp = artwork.load(item, w, h) ?: return@execute
            holder.art.post {
                val pos = holder.bindingAdapterPosition
                val now = if (pos != RecyclerView.NO_POSITION) tileAt(pos) else null
                if (now?.app?.key == item.key && now.favourite == row.favourite) {
                    holder.art.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Artwork is rasterised at the focused (scaled) size so it stays sharp when popped. */
    fun artSize(favourite: Boolean): Pair<Int, Int> {
        val base = if (favourite) favouriteTileWidth else appTileWidth
        val scale = if (favourite) TileStyler.FAVOURITE_SCALE else TileStyler.APP_SCALE
        val w = (base * scale).toInt()
        return w to (w * 9f / 16f).toInt()
    }

    companion object {
        const val SPAN = 60
        const val FAVOURITE_COLUMNS = 3
        const val APP_COLUMNS = 4
        private const val TYPE_HEADER = 0
        private const val TYPE_TILE = 1
    }
}
