package com.mdev64.simpletvlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Carousel home.
 *
 *   clock ……………………………………………… [apps | wifi vpn ⚙ ⚙]   status line
 *        ░side░   [  centred app card    ]   ░side░        ← LEFT/RIGHT loops forever
 *                     3 of 12 · hints · ● ○ ○
 *                           OTHER APPS ⌄                   ← DOWN opens the Other apps page
 *
 * Pins are unlimited and appear in the carousel in pin order. The All apps page is an
 * in-activity layer over the home layer (home focus is blocked while it is open).
 */
class MainActivity : Activity() {

    private companion object {
        const val CLOCK_TICK_MS = 15_000L
        const val REQ_UNINSTALL = 51
        const val REQ_LOCATION = 52
        const val ALL_APPS_COLUMNS = 5
        const val MAX_DOTS = 15
        /** All apps open/close sweep; home travels a third of the screen up as it fades. */
        const val SWEEP_MS = 420L
        const val SWEEP_BACK_MS = 360L
        const val HOME_TRAVEL = 0.35f
        /** Edge fade, screen edge → inward: 0.95 × (1 − smoothstep(0.05, 0.9, x)). */
        val EDGE_FADE_ALPHAS = intArrayOf(0xF2, 0xF2, 0xDE, 0xAD, 0x6E, 0x33, 0x09, 0x00, 0x00)
        val EDGE_FADE_STOPS = floatArrayOf(0f, 0.05f, 0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.9f, 1f)
        var cachedApps: List<AppItem> = emptyList()
        var sharedArtwork: ArtworkLoader? = null
    }

    private lateinit var root: FrameLayout
    private lateinit var bootOverlay: View
    private lateinit var homeLayer: FrameLayout
    private lateinit var carousel: CarouselView
    private lateinit var topGroup: ViewGroup
    private lateinit var prefs: Prefs
    private lateinit var artwork: ArtworkLoader

    private lateinit var greeting: TextView
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var topStatus: TextView
    private lateinit var appCaption: TextView
    private lateinit var pageDots: LinearLayout
    private lateinit var chevron: ImageView
    private lateinit var hintOpen: View
    private lateinit var hintOptions: View

    private lateinit var statusWifi: ImageView
    private lateinit var statusVpn: ImageView
    private lateinit var btnLauncherSettings: ImageView
    private lateinit var btnTvSettings: ImageView

    private lateinit var allAppsPage: View
    private lateinit var allAppsCount: TextView
    private lateinit var allAppsFocus: TextView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: HomeAdapter

    private lateinit var screenSaver: ScreenSaverOverlay

    private val worker = Executors.newSingleThreadExecutor()
    /** Network probing + VPN geo lookup; kept off [worker] so artwork loads never queue behind it. */
    private val netWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var allApps: List<AppItem> = emptyList()
    private var visibleApps: List<AppItem> = emptyList()
    private var activeMenu: AppMenuOverlay? = null
    private var initialFocusPending = true
    private var lastTopIndex = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var isWifiConnected = false
    private var isEthernet = false
    private var currentWifiSsid: String? = null
    private var isVpnConnected = false
    private var currentVpnCountry: String? = null
    private var activeVpnPackage: String? = null
    private var vpnCountryFetching = false
    private var lastVpnLookupMs = 0L

    private var timeFmt = SimpleDateFormat("h:mm", Locale.getDefault())
    private var amPmFmt: SimpleDateFormat? = SimpleDateFormat("a", Locale.getDefault())
    private var dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    private val gridListener = object : HomeAdapter.Listener {
        override fun onAppClick(item: AppItem) = launch(item)
        override fun onAppLongPress(item: AppItem, favourite: Boolean) = showAppMenu(item)
    }

    private val carouselListener = object : CarouselView.Listener {
        override fun onCenterChanged(index: Int, item: AppItem?) = updateDetails()

        override fun loadArtwork(
            item: AppItem, widthPx: Int, heightPx: Int, onReady: (Bitmap) -> Unit
        ): Bitmap? {
            artwork.cached("$widthPx x $heightPx ${item.key}")?.let { return it }
            if (worker.isShutdown) return null
            worker.execute {
                val bmp = artwork.load(item, widthPx, heightPx) ?: return@execute
                main.post { onReady(bmp) }
            }
            return null
        }

        override fun describe(item: AppItem): String = appDetails.getOrPut(item.key) {
            val parts = ArrayList<String>(3)
            val info = runCatching {
                packageManager.getPackageInfo(item.component.packageName, 0)
            }.getOrNull()
            info?.applicationInfo?.let { ai ->
                if (ai.category != ApplicationInfo.CATEGORY_UNDEFINED) {
                    ApplicationInfo.getCategoryTitle(this@MainActivity, ai.category)
                        ?.let { parts += it.toString() }
                }
            }
            if (item.systemApp) parts += "System app"
            info?.versionName?.takeIf { it.isNotBlank() && it.length <= 18 }?.let {
                parts += if (it.first().isDigit()) "v$it" else it
            }
            if (parts.isEmpty()) parts += if (item.isLeanback) "TV app" else "App"
            parts.joinToString("  ·  ")
        }
    }

    /** Card detail lines, memoised per app key (cleared when packages change). */
    private val appDetails = HashMap<String, String>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // An update sends REMOVED(replacing) then REPLACED; scanning in between would
            // see the app as uninstalled and prune its pin. Wait for REPLACED instead.
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return
            cachedApps = emptyList()
            appDetails.clear()
            if (::prefs.isInitialized) {
                DefaultLauncherHelper.rememberPreviousLauncherIfNeeded(this@MainActivity, prefs)
                if (DefaultLauncherHelper.otherInstalledLaunchers(this@MainActivity).isEmpty()) {
                    DefaultLauncherHelper.setHomeAliasEnabled(this@MainActivity, true)
                }
            }
            refreshApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The root always paints (its own bg colour or the photo), so the window background
        // is pure overdraw: one full-screen fill per frame on a fill-rate-bound GPU.
        window.setBackgroundDrawable(null)

        prefs = Prefs(this)
        artwork = sharedArtwork ?: ArtworkLoader(applicationContext.packageManager).also {
            sharedArtwork = it
        }
        root = findViewById(R.id.launcher_root)
        screenSaver = ScreenSaverOverlay(this, root, prefs)
        bootOverlay = findViewById(R.id.boot_overlay)
        findViewById<android.widget.ProgressBar>(R.id.boot_progress).indeterminateTintList =
            ColorStateList.valueOf(prefs.accent)
        if (cachedApps.isEmpty()) {
            bootOverlay.visibility = View.VISIBLE
            bootOverlay.alpha = 1f
        }

        homeLayer = findViewById(R.id.home_layer)
        topGroup = findViewById(R.id.top_group)
        greeting = findViewById(R.id.greeting)
        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)
        topStatus = findViewById(R.id.top_status)
        appCaption = findViewById(R.id.app_caption)
        pageDots = findViewById(R.id.page_dots)
        chevron = findViewById(R.id.all_apps_chevron)
        hintOpen = findViewById(R.id.hint_open)
        hintOptions = findViewById(R.id.hint_options)

        statusWifi = findViewById(R.id.status_wifi)
        statusVpn = findViewById(R.id.status_vpn)
        btnLauncherSettings = findViewById(R.id.btn_launcher_settings)
        btnTvSettings = findViewById(R.id.btn_tv_settings)

        statusWifi.setOnClickListener {
            when {
                isEthernet && !isWifiConnected -> openNetworkSettings()
                !requestLocationForSsidOnce() -> toggleWifi()
            }
        }
        statusVpn.setOnClickListener { handleVpnClick() }
        btnLauncherSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnTvSettings.setOnClickListener { openTvSettings() }

        carousel = CarouselView(this).apply {
            listener = carouselListener
            setOnClickListener { centerItem?.let { launch(it) } ?: openAllApps() }
            setOnLongClickListener {
                centerItem?.let { showCarouselMenu(it) }
                true
            }
        }
        findViewById<FrameLayout>(R.id.carousel_host).addView(
            carousel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        allAppsPage = findViewById(R.id.all_apps_page)
        allAppsCount = findViewById(R.id.all_apps_count)
        allAppsFocus = findViewById(R.id.all_apps_focus)
        adapter = HomeAdapter(artwork, worker, gridListener, ALL_APPS_COLUMNS)
        computeTileWidths()
        grid = findViewById(R.id.apps_grid)
        grid.layoutManager = BandLayoutManager(this).apply { spanSizeLookup = adapter.spanLookup }
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(20)
        grid.isFocusable = false
        grid.itemAnimator = null
        grid.adapter = adapter

        try {
            val pkgFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            registerReceiver(packageReceiver, pkgFilter)
        } catch (_: Exception) { }

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@addOnGlobalFocusChangeListener
            if (isInside(newFocus, grid)) {
                adapter.tileAt(positionIn(grid, newFocus))?.let { showGridFocus(it.app) }
            }
            if (newFocus === statusWifi) {
                getSystemService(ConnectivityManager::class.java)?.let { refreshNetworkStatus(it) }
            }
            refreshTopStatus()
            updateCarouselCues(newFocus)
        }
    }

    // ------------------------------------------------------------ layout

    private fun computeTileWidths() {
        val usable = resources.displayMetrics.widthPixels - 2 * dp(16)
        adapter.appTileWidth = usable / ALL_APPS_COLUMNS -
            2 * resources.getDimensionPixelSize(R.dimen.tile_margin_h)
        adapter.favouriteTileWidth = adapter.appTileWidth
    }

    /** Keeps the focused All-apps tile inside a band so its scale + focus pill never clip. */
    private inner class BandLayoutManager(ctx: Context) :
        GridLayoutManager(ctx, HomeAdapter.SPAN) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: android.graphics.Rect,
            immediate: Boolean, focusedChildVisible: Boolean
        ): Boolean {
            val top = child.top + rect.top
            val bottom = child.top + rect.bottom
            val minTop = parent.paddingTop + dp(10)
            val maxBottom = parent.height - parent.paddingBottom
            val dy = when {
                top < minTop -> top - minTop
                bottom > maxBottom -> bottom - maxBottom
                else -> 0
            }
            if (dy == 0) return false
            if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
            return true
        }
    }

    // ------------------------------------------------------------ carousel details

    private fun updateDetails() {
        val item = carousel.centerItem
        val n = visiblePinnedCount()
        // With nothing pinned OK opens All apps and hold-OK does nothing: say so.
        findViewById<TextView>(R.id.hint_open_label).text = if (item == null) "Other apps" else "Open"
        hintOptions.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        if (item == null) {
            appCaption.visibility = View.GONE
            renderDots(0, 0)
            return
        }
        // Name + details live on the card; position is the page dots, or text past MAX_DOTS.
        if (n > MAX_DOTS) {
            appCaption.text = "${carousel.index + 1} of $n"
            appCaption.visibility = View.VISIBLE
        } else {
            appCaption.visibility = View.GONE
        }
        renderDots(n, carousel.index)
    }

    /** Footer hints + position describe the carousel: full strength only while it has focus. */
    private fun updateCarouselCues(focus: View?) {
        val a = if (focus === carousel) 1f else 0.35f
        for (v in arrayOf(hintOpen, hintOptions, pageDots, appCaption)) {
            if (v.alpha != a) v.animate().alpha(a).setDuration(150L).start()
        }
    }

    private var pinnedCount = 0
    private fun visiblePinnedCount() = pinnedCount

    private fun renderDots(count: Int, current: Int) {
        if (count < 2 || count > MAX_DOTS) {
            pageDots.visibility = View.GONE
            return
        }
        pageDots.visibility = View.VISIBLE
        if (pageDots.childCount != count) {
            pageDots.removeAllViews()
            repeat(count) {
                pageDots.addView(View(this), LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
        }
        val accent = prefs.accent
        for (i in 0 until count) {
            val dot = pageDots.getChildAt(i)
            val on = i == current
            dot.background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(if (on) accent else 0x59FFFFFF)
            }
            val lp = dot.layoutParams
            val w = if (on) dp(18) else dp(6)
            if (lp.width != w) {
                lp.width = w
                dot.layoutParams = lp
            }
        }
    }

    private fun showGridFocus(item: AppItem) {
        allAppsFocus.text = item.label
    }

    // ------------------------------------------------------------ All apps page

    private val isAllAppsOpen get() = allAppsPage.visibility == View.VISIBLE
    private val sweep = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun openAllApps() {
        if (isAllAppsOpen) return
        if (adapter.firstTilePosition() < 0) {
            Toast.makeText(this, "Every app is already on your home row", Toast.LENGTH_SHORT).show()
            return
        }
        homeLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Sweep: home slides up and away while the page rises from the bottom, in lockstep.
        val h = root.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
        allAppsPage.visibility = View.VISIBLE
        allAppsPage.alpha = 1f
        allAppsPage.translationY = h
        allAppsPage.animate().translationY(0f).setDuration(SWEEP_MS).setInterpolator(sweep).start()
        homeLayer.animate().translationY(-h * HOME_TRAVEL).alpha(0f).setDuration(SWEEP_MS)
            .setInterpolator(sweep)
            // Hidden under the opaque page: stop drawing it while the grid scrolls.
            .withEndAction { if (isAllAppsOpen) homeLayer.visibility = View.INVISIBLE }
            .start()
        // Always start on the first app, top row: one UP closes the page again.
        val pos = adapter.firstTilePosition()
        if (pos >= 0) {
            grid.scrollToPosition(pos)
            grid.post { focusTile(pos) }
        }
    }

    private fun closeAllApps() {
        if (!isAllAppsOpen) return
        val h = root.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
        // Reverse sweep: the page drops away and home comes back down from above.
        allAppsPage.animate().translationY(h).setDuration(SWEEP_BACK_MS).setInterpolator(sweep)
            .withEndAction { allAppsPage.visibility = View.GONE }.start()
        homeLayer.visibility = View.VISIBLE
        homeLayer.animate().translationY(0f).alpha(1f).setDuration(SWEEP_BACK_MS)
            .setInterpolator(sweep).withEndAction(null).start()
        homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        carousel.requestFocus()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::screenSaver.isInitialized && screenSaver.isShowing) {
            screenSaver.dismiss(restoreFocus = false)
        }
        activeMenu?.dismiss()
        activeMenu = null
        if (::carousel.isInitialized) {
            closeAllApps()
            carousel.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        DefaultLauncherHelper.rememberPreviousLauncherIfNeeded(this, prefs)
        if (DefaultLauncherHelper.otherInstalledLaunchers(this).isEmpty()) {
            DefaultLauncherHelper.setHomeAliasEnabled(this, true)
        }
        applyClock()
        applyTheme()
        applyBackground()
        registerNetworkStatus()
        refreshApps()
        screenSaver.resetIdleTimer()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, CLOCK_TICK_MS)
        // Coming back from TV settings / launcher settings (opened from the top bar): land on
        // the centred app, not the button that opened them.
        if (::carousel.isInitialized && !isAllAppsOpen && activeMenu?.isShowing != true) {
            carousel.post { if (!isAllAppsOpen) carousel.requestFocus() }
        }
    }

    override fun onPause() {
        super.onPause()
        screenSaver.stopIdleTimer()
        main.removeCallbacks(ticker)
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) { }
        }
        networkCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) { }
        // Drop pending delayed refreshes (e.g. the post-uninstall rescan) before the
        // executors go away, or they would hit a shut-down worker.
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        netWorker.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) {
            cachedApps = emptyList()
            refreshApps()
            main.postDelayed({
                cachedApps = emptyList()
                refreshApps()
            }, 900L)
        }
    }

    // ------------------------------------------------------------ key routing

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screenSaver.onKeyEvent(event)) return true
        val menu = activeMenu
        if (menu != null && menu.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                menu.dismiss()
                activeMenu = null
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = currentFocus

        if (isAllAppsOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                closeAllApps()
                return true
            }
            if (focused == null || !isInside(focused, grid)) return super.dispatchKeyEvent(event)
            val pos = positionIn(grid, focused)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos in 0 until ALL_APPS_COLUMNS) {
                        closeAllApps()
                    } else {
                        grid.focusSearch(focused, View.FOCUS_UP)
                            ?.takeIf { it !== focused && isInside(it, grid) }?.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    grid.focusSearch(focused, View.FOCUS_DOWN)
                        ?.takeIf { it !== focused && isInside(it, grid) }?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos > 0 && sameRow(pos, pos - 1)) focusTile(pos - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (adapter.tileAt(pos + 1) != null && sameRow(pos, pos + 1)) focusTile(pos + 1)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    adapter.tileAt(pos)?.let { showAppMenu(it.app) }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (focused == null) return super.dispatchKeyEvent(event)
        val inTop = isInside(focused, topGroup)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focused === carousel) {
                    val buttons = topButtons()
                    buttons[lastTopIndex.coerceIn(0, buttons.size - 1)].requestFocus()
                    return true
                }
                if (inTop) return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focused === carousel) {
                    openAllApps()
                    return true
                }
                if (inTop) {
                    lastTopIndex = topButtons().indexOf(focused).coerceAtLeast(0)
                    carousel.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inTop) {
                    val buttons = topButtons()
                    val idx = buttons.indexOf(focused)
                    val next = idx + if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    if (idx >= 0 && next in buttons.indices) {
                        lastTopIndex = next
                        buttons[next].requestFocus()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (inTop) {
                    carousel.requestFocus()
                    return true
                }
                if (DefaultLauncherHelper.isDefaultHome(this) || prefs.defaultLauncherEnabled) {
                    if (focused === carousel && carousel.index != 0) carousel.jumpTo(0)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (focused === carousel) {
                    carousel.centerItem?.let { showCarouselMenu(it) }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun topButtons(): List<View> =
        listOf(statusWifi, statusVpn, btnLauncherSettings, btnTvSettings)

    private fun sameRow(a: Int, b: Int): Boolean =
        adapter.spanLookup.getSpanGroupIndex(a, HomeAdapter.SPAN) ==
            adapter.spanLookup.getSpanGroupIndex(b, HomeAdapter.SPAN)

    private fun isInside(child: View, container: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === container) return true
            v = v.parent as? View
        }
        return false
    }

    private fun positionIn(rv: RecyclerView, focused: View): Int {
        if (!isInside(focused, rv)) return RecyclerView.NO_POSITION
        var v: View? = focused
        while (v != null && v.parent !== rv) v = v.parent as? View
        return v?.let { rv.getChildViewHolder(it)?.bindingAdapterPosition }
            ?: RecyclerView.NO_POSITION
    }

    private fun focusTile(position: Int) {
        if (adapter.tileAt(position) == null) return
        if (focusTileNow(position)) return
        grid.scrollToPosition(position)
        grid.post { focusTileNow(position) }
    }

    private fun focusTileNow(position: Int): Boolean {
        val rootView = grid.findViewHolderForAdapterPosition(position)?.itemView
            ?: grid.layoutManager?.findViewByPosition(position)
            ?: return false
        val tileView = rootView.findViewById<View>(R.id.tile) ?: rootView
        return tileView.requestFocus()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ------------------------------------------------------------ clock

    private fun applyClock() {
        clockTime.visibility = if (prefs.clockTime) View.VISIBLE else View.GONE
        clockDate.visibility = if (prefs.clockDate) View.VISIBLE else View.GONE
        if (prefs.clock24h) {
            timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            amPmFmt = null
        } else {
            timeFmt = SimpleDateFormat("h:mm", Locale.getDefault())
            amPmFmt = SimpleDateFormat("a", Locale.getDefault())
        }
        dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        renderClock()
    }

    private fun renderClock() {
        val now = Date()
        val time = timeFmt.format(now)
        val suffix = amPmFmt?.format(now)
        clockTime.text = if (suffix == null) time else SpannableString("$time $suffix").apply {
            setSpan(RelativeSizeSpan(0.42f), time.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        clockDate.text = dateFmt.format(now)
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
    }

    /** Drives the clock; cheap and immune to TextClock's format quirks. */
    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            main.postDelayed(this, CLOCK_TICK_MS)
        }
    }

    // ------------------------------------------------------------ theme

    private fun applyTheme() {
        val accent = prefs.accent
        if (adapter.accent != accent) {
            adapter.accent = accent
            adapter.notifyDataSetChanged()
        }
        greeting.setTextColor(accent)
        chevron.imageTintList = ColorStateList.valueOf(accent)
        topButtons().forEach { styleHeaderButton(it as ImageView, accent) }
        renderDots(pinnedCount, carousel.index)
    }

    /** Circular header buttons: bare accent glyph at rest, accent fill + contrast glyph on focus. */
    private fun styleHeaderButton(button: ImageView, accent: Int) {
        button.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
            })
        }
        val onAccent = if (isLight(accent)) 0xFF141418.toInt() else 0xFFFFFFFF.toInt()
        button.imageTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(onAccent, accent)
        )
    }

    /** Perceived brightness, used to keep glyphs legible on the accent fill. */
    private fun isLight(color: Int): Boolean {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b > 0.6f
    }

    // ------------------------------------------------------------ status line

    private fun netTitle() = if (isEthernet && !isWifiConnected) "Ethernet" else "Wi-Fi"

    private fun netValue(): String {
        if (isEthernet && !isWifiConnected) return "Connected"
        val wifiEnabled = try {
            applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        } catch (_: Exception) { isWifiConnected }
        return when {
            isWifiConnected && !currentWifiSsid.isNullOrBlank() -> currentWifiSsid!!
            isWifiConnected && !hasFineLocation() && canAskLocation() -> "Connected · OK to show name"
            isWifiConnected -> "Connected"
            wifiEnabled -> "Not connected"
            else -> "Off"
        }
    }

    private fun vpnValue() = when {
        isVpnConnected && !currentVpnCountry.isNullOrBlank() -> "On · $currentVpnCountry"
        isVpnConnected -> "On"
        else -> "Off"
    }

    private fun refreshTopStatus() {
        val f = currentFocus
        val text = when {
            f === statusWifi -> "${netTitle()}  ·  ${netValue()}"
            f === statusVpn -> "VPN  ·  ${vpnValue()}"
            f === btnLauncherSettings -> "Launcher settings"
            f === btnTvSettings -> "TV settings"
            else -> "${netValue()}   ·   VPN ${vpnValue().lowercase(Locale.getDefault())}"
        }
        topStatus.text = text
        topStatus.setTextColor(if (f != null && isInside(f, topGroup)) 0xFFFFFFFF.toInt() else 0xB8FFFFFF.toInt())
    }

    private fun renderNetworkRows() {
        if (isEthernet && !isWifiConnected) {
            statusWifi.setImageResource(R.drawable.ic_ethernet)
            statusWifi.contentDescription = getString(R.string.desc_ethernet)
        } else {
            statusWifi.setImageResource(if (isWifiConnected) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
            statusWifi.contentDescription = getString(
                if (isWifiConnected) R.string.desc_wifi else R.string.desc_wifi_off
            )
        }
        statusVpn.setImageResource(if (isVpnConnected) R.drawable.ic_vpn else R.drawable.ic_vpn_off)
        statusVpn.contentDescription = if (isVpnConnected) getString(R.string.desc_vpn) else "VPN off"
        refreshTopStatus()
    }

    private fun toggleWifi() {
        // targetSdk >= 29: setWifiEnabled() is a no-op that returns false on Android 10+,
        // so only attempt it on older TVs and go straight to the system UI otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openWifiSettings()
            return
        }
        val wm = try { applicationContext.getSystemService(WifiManager::class.java) } catch (_: Exception) { null }
        if (wm != null) {
            val currentlyEnabled = try { wm.isWifiEnabled } catch (_: Exception) { isWifiConnected }
            @Suppress("DEPRECATION")
            val toggled = try { wm.setWifiEnabled(!currentlyEnabled) } catch (_: Exception) { false }
            if (toggled) {
                topStatus.text = if (!currentlyEnabled) "Wi-Fi  ·  Turning on…" else "Wi-Fi  ·  Off"
                main.postDelayed({
                    getSystemService(ConnectivityManager::class.java)?.let { refreshNetworkStatus(it) }
                }, 1500L)
                return
            }
        }
        openWifiSettings()
    }

    // ------------------------------------------------------------ menus

    private fun showCarouselMenu(item: AppItem) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val order = prefs.pins()
        val index = order.indexOf(item.key)
        if (order.size > 1) {
            if (index > 0) actions += "Move left" to {
                prefs.movePin(item.key, up = true); rebuild()
            }
            if (index >= 0 && index < order.size - 1) actions += "Move right" to {
                prefs.movePin(item.key, up = false); rebuild()
            }
        }
        actions += "Remove from home" to {
            prefs.togglePin(item.key)
            rebuild()
        }
        actions += "Hide app" to {
            prefs.hide(item.key)
            rebuild()
            Toast.makeText(this, "${item.label} hidden — restore in launcher settings",
                Toast.LENGTH_SHORT).show()
        }
        actions += "App info" to openAppInfo(item)
        if (!item.systemApp) actions += "Uninstall" to uninstall(item)
        actions += "Cancel" to {}
        showOverlayMenu(item, actions)
    }

    private fun showAppMenu(item: AppItem) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        // The grid only lists apps that are not on home, so pinning removes the tile:
        // keep focus at the same spot (or close the page when it was the last one).
        actions += "Add to home" to {
            val pos = adapter.positionOf(item.key)
            prefs.togglePin(item.key)
            rebuild()
            if (adapter.firstTilePosition() < 0) closeAllApps()
            else grid.post { focusTile(pos.coerceAtMost(adapter.itemCount - 1).coerceAtLeast(0)) }
            Toast.makeText(this, "${item.label} added to home", Toast.LENGTH_SHORT).show()
        }
        actions += "Hide app" to {
            val pos = adapter.positionOf(item.key)
            prefs.hide(item.key)
            rebuild()
            grid.post { focusTile(pos.coerceAtMost(adapter.itemCount - 1).coerceAtLeast(0)) }
            Toast.makeText(this, "${item.label} hidden — restore in launcher settings",
                Toast.LENGTH_SHORT).show()
        }
        actions += "App info" to openAppInfo(item)
        if (!item.systemApp) actions += "Uninstall" to uninstall(item)
        actions += "Cancel" to {}
        showOverlayMenu(item, actions)
    }

    private fun showOverlayMenu(item: AppItem, actions: List<Pair<String, () -> Unit>>) {
        activeMenu?.dismiss()
        activeMenu = AppMenuOverlay(
            this,
            root,
            title = item.label,
            subtitle = if (item.systemApp) "System app" else null,
            actions = actions
        ).also { it.show() }
    }

    // ------------------------------------------------------------ data

    private fun refreshApps() {
        if (allApps.isEmpty() && cachedApps.isNotEmpty()) {
            allApps = cachedApps
        }
        if (allApps.isNotEmpty()) {
            rebuild()
            dismissBootOverlay()
        }
        if (worker.isShutdown) return
        worker.execute {
            val isColdBoot = allApps.isEmpty()
            if (isColdBoot && prefs.bgMode == 2 && prefs.bgUri != null) {
                WallpaperStore.loadDisplay(applicationContext, prefs)
            }
            val apps = scanApps()
            if (isColdBoot) preloadInitialArtwork(apps)
            main.post {
                if (isFinishing || isDestroyed) return@post
                val changed = apps != allApps || adapter.itemCount == 0
                allApps = apps
                cachedApps = apps
                if (apps.isNotEmpty()) prefs.prunePins(apps.mapTo(HashSet()) { it.key })
                if (isColdBoot && prefs.bgMode == 2) applyBackground()
                if (changed) rebuild()
                dismissBootOverlay()
            }
        }
    }

    /** First carousel cards + first screen of All apps, decoded before the boot overlay fades. */
    private fun preloadInitialArtwork(apps: List<AppItem>) {
        val hidden = prefs.hidden()
        val visible = apps.filter { it.key !in hidden }
        val pinned = prefs.pins().mapNotNull { k -> visible.firstOrNull { it.key == k } }
        val cw = carousel.artWidth
        val ch = carousel.artHeight
        (pinned.take(3) + pinned.takeLast(2)).distinctBy { it.key }.forEach { artwork.load(it, cw, ch) }
        val (aw, ah) = adapter.artSize(false)
        visible.take(15).forEach { artwork.load(it, aw, ah) }
    }

    private fun dismissBootOverlay() {
        if (bootOverlay.visibility != View.VISIBLE) return
        bootOverlay.isFocusable = false
        bootOverlay.isClickable = false
        bootOverlay.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { bootOverlay.visibility = View.GONE }
            .start()
    }

    /** Pinned apps → carousel (in pin order); every other visible app → Other apps page. */
    private fun rebuild() {
        val hidden = prefs.hidden()
        visibleApps = allApps.filter { it.key !in hidden }
        val pinned = prefs.pins().mapNotNull { k -> visibleApps.firstOrNull { it.key == k } }
        pinnedCount = pinned.size
        carousel.submit(pinned)

        val pinnedKeys = pinned.mapTo(HashSet()) { it.key }
        val others = visibleApps.filter { it.key !in pinnedKeys }
        adapter.submit(others.mapIndexed { i, app ->
            HomeAdapter.Row.Tile(app, false, i, others.size)
        })
        allAppsCount.text = others.size.toString()
        refreshTopStatus()

        if (initialFocusPending && allApps.isNotEmpty()) {
            initialFocusPending = false
            carousel.post { if (!isAllAppsOpen) carousel.requestFocus() }
        }
    }

    // ------------------------------------------------------------ network

    private fun registerNetworkStatus() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        refreshNetworkStatus(cm)
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetworkStatus(cm)
            override fun onLost(network: Network) = refreshNetworkStatus(cm)
            override fun onCapabilitiesChanged(
                network: Network, caps: NetworkCapabilities
            ) = refreshNetworkStatus(cm)
        }
        try {
            val req = android.net.NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (_: Exception) { }
    }

    private class NetSnapshot(
        val wifi: Boolean,
        val vpn: Boolean,
        val eth: Boolean,
        val ssid: String?,
        val vpnPkg: String?
    )

    /**
     * Safe from any thread (NetworkCallback runs on a binder thread): probing happens on
     * [netWorker], and every field write / view update happens on the main thread.
     */
    private fun refreshNetworkStatus(cm: ConnectivityManager) {
        if (netWorker.isShutdown) return
        try {
            netWorker.execute {
                val snap = readNetworkSnapshot(cm)
                main.post { if (!isDestroyed) applyNetworkSnapshot(snap) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun readNetworkSnapshot(cm: ConnectivityManager): NetSnapshot {
        var wifi = false
        var vpn = false
        var eth = false
        var detectedSsid: String? = null
        var detectedVpnPkg: String? = null

        try {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true
                    val lp = cm.getLinkProperties(net)
                    val addrs = lp?.linkAddresses?.joinToString(" ") { it.address.hostAddress ?: "" } ?: ""
                    detectedVpnPkg = detectVpnPackage(addrs) ?: detectedVpnPkg
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifi = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val info = caps.transportInfo as? WifiInfo
                        val s = cleanSsid(info?.ssid)
                        if (s != null) detectedSsid = s
                    }
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true
            }
        } catch (_: Exception) { }

        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val n = iface.name.lowercase(Locale.US)
                if (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("ppp") || n.startsWith("tap")) {
                    vpn = true
                    val addrs = iface.inetAddresses.toList().joinToString(" ") { it.hostAddress ?: "" }
                    detectedVpnPkg = detectVpnPackage(addrs) ?: detectedVpnPkg
                }
            }
        } catch (_: Exception) { }

        if (wifi && detectedSsid == null) {
            try {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                detectedSsid = cleanSsid(wm?.connectionInfo?.ssid)
            } catch (_: Exception) { }
        }

        return NetSnapshot(wifi, vpn, eth, detectedSsid, detectedVpnPkg)
    }

    /** Main thread only. */
    private fun applyNetworkSnapshot(s: NetSnapshot) {
        val vpnChanged = s.vpn != isVpnConnected
        isWifiConnected = s.wifi
        isEthernet = s.eth
        currentWifiSsid = s.ssid
        isVpnConnected = s.vpn
        if (s.vpnPkg != null) activeVpnPackage = s.vpnPkg

        if (!s.vpn) {
            currentVpnCountry = null
            lastVpnLookupMs = 0L
        } else if (vpnChanged || currentVpnCountry == null) {
            fetchVpnCountry(force = vpnChanged)
        }
        renderNetworkRows()
    }

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Since targetSdk 29 the Wi-Fi SSID is only visible with ACCESS_FINE_LOCATION granted at
     * runtime. Only on an explicit OK press, and only while the system would still show its
     * dialog (first ask, or one retry after a denial) — afterwards the row just opens Wi-Fi
     * settings. @return true when asked.
     */
    private fun canAskLocation(): Boolean = !hasFineLocation() && (!prefs.locationAsked ||
        shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION))

    private fun requestLocationForSsidOnce(): Boolean {
        if (!isWifiConnected || !currentWifiSsid.isNullOrBlank() || !canAskLocation()) return false
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOCATION
        )
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            // Empty results = dialog was cancelled without an answer; allow asking again.
            if (grantResults.isNotEmpty()) prefs.locationAsked = true
            getSystemService(ConnectivityManager::class.java)?.let { refreshNetworkStatus(it) }
        }
    }

    private fun detectVpnPackage(addrs: String): String? = when {
        addrs.contains("10.2.0.") || addrs.contains("2a07:b944:") -> "ch.protonvpn.android"
        addrs.contains("10.64.0.") -> "net.mullvad.mullvadvpn"
        else -> null
    }

    private fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.removeSurrounding("\"").trim()
        if (trimmed.isEmpty() || trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        return trimmed
    }

    private fun fetchVpnCountry(force: Boolean) {
        val now = System.currentTimeMillis()
        if (vpnCountryFetching) return
        if (!force && currentVpnCountry != null && now - lastVpnLookupMs < 30_000L) return
        vpnCountryFetching = true
        lastVpnLookupMs = now

        Thread {
            val country = queryTraceCountry("https://1.1.1.1/cdn-cgi/trace")
                ?: queryTraceCountry("https://www.cloudflare.com/cdn-cgi/trace")
            main.post {
                vpnCountryFetching = false
                if (isVpnConnected && !country.isNullOrBlank()) {
                    currentVpnCountry = country
                    renderNetworkRows()
                }
            }
        }.start()
    }

    private fun queryTraceCountry(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                useCaches = false
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val locLine = body.lineSequence().firstOrNull { it.startsWith("loc=") } ?: return null
            val code = locLine.substringAfter("loc=").trim().uppercase(Locale.US)
            if (code.length != 2 || code == "XX" || code == "T1") return null
            Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun openWifiSettings() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Intent(Settings.Panel.ACTION_WIFI))
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { }
        }
        openTvSettings()
    }

    private fun openNetworkSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            openWifiSettings()
        }
    }

    private fun handleVpnClick() {
        val vpnApps = findInstalledVpnApps()
        if (vpnApps.isEmpty()) {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, "No VPN app installed", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (vpnApps.size == 1) {
            activeVpnPackage = vpnApps[0].component.packageName
            launch(vpnApps[0])
            return
        }

        val actions = ArrayList<Pair<String, () -> Unit>>()
        for (app in vpnApps) {
            val active = isVpnConnected && app.component.packageName == activeVpnPackage
            val label = when {
                active -> "${app.label} — active, open to disconnect"
                isVpnConnected -> "Switch to ${app.label}"
                else -> "Connect with ${app.label}"
            }
            actions += label to {
                activeVpnPackage = app.component.packageName
                launch(app)
            }
        }
        // Android TV ships no VPN settings screen (ACTION_VPN_SETTINGS resolves to nothing
        // here), so only offer it where it exists. No network-settings fallback: the Wi-Fi
        // button already opens that page.
        val vpnSettings = Intent(Settings.ACTION_VPN_SETTINGS)
        if (vpnSettings.resolveActivity(packageManager) != null) {
            actions += "VPN system settings" to {
                try {
                    startActivity(vpnSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
        }
        actions += "Cancel" to {}

        activeMenu?.dismiss()
        activeMenu = AppMenuOverlay(
            this,
            root,
            title = if (isVpnConnected) "VPN is on" else "Connect VPN",
            subtitle = if (isVpnConnected && !currentVpnCountry.isNullOrBlank())
                "Exit location · $currentVpnCountry" else "Choose a VPN app",
            actions = actions
        ).also {
            it.returnFocus = carousel // Cancel / BACK lands on the centred app
            it.show()
        }
    }

    private fun findInstalledVpnApps(): List<AppItem> {
        val pm = packageManager
        val vpnServices = try {
            pm.queryIntentServices(Intent("android.net.VpnService"), 0) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val vpnPkgs = vpnServices.mapNotNull { it.serviceInfo?.packageName }.toHashSet()
        if (vpnPkgs.isEmpty()) return emptyList()
        return allApps.filter { it.component.packageName in vpnPkgs }
    }

    // ------------------------------------------------------------ app actions

    private fun openAppInfo(item: AppItem): () -> Unit = {
        val pkgUri = Uri.fromParts("package", item.component.packageName, null)
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "App info not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstall(item: AppItem): () -> Unit = {
        val pkgUri = Uri.fromParts("package", item.component.packageName, null)
        try {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkgUri).apply {
                putExtra("android.intent.extra.UNINSTALL_ALL_USERS", false)
            }
            startActivityForResult(intent, REQ_UNINSTALL)
        } catch (_: Exception) {
            try {
                startActivityForResult(Intent(Intent.ACTION_DELETE, pkgUri), REQ_UNINSTALL)
            } catch (_: Exception) {
                openAppInfo(item).invoke()
            }
        }
    }

    /** [bmp] is already the display copy (blurred or full) from [WallpaperStore]. */
    private fun showWallpaper(image: ImageView, scrim: View, bmp: Bitmap) {
        val current = (image.drawable as? BitmapDrawable)?.bitmap
        if (current !== bmp) {
            image.setImageDrawable(BitmapDrawable(resources, bmp))
            // The swapped-out frame (e.g. the 8 MB full image after enabling blur) is only
            // referenced by the ImageView; free its native memory now instead of at next GC.
            if (current != null && !current.isRecycled && !WallpaperStore.holds(current)) {
                current.recycle()
            }
        }
        image.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        // An opaque photo covers the root colour: skip that fill, and cache photo + scrim
        // as one GPU texture (they never change while the carousel animates).
        if (!bmp.hasAlpha()) root.background = null
        setPhotoLayer(true)
        updateCardFrost(bmp)
    }

    /**
     * Screen-edge fade over the carousel (under the top bar and footer): [color] at the
     * edge falling off along a smoothstep to nothing ~0.9 of the way in. Two static
     * gradients — nothing per card, no offscreen layers — so moving cards fade too.
     */
    private fun applyEdgeFade(color: Int) {
        fun strip(left: Boolean): GradientDrawable {
            val rgb = color and 0x00FFFFFF
            val colors = EDGE_FADE_ALPHAS.map { (it shl 24) or rgb }.toIntArray()
            val o = if (left) GradientDrawable.Orientation.LEFT_RIGHT
            else GradientDrawable.Orientation.RIGHT_LEFT
            return GradientDrawable(o, colors).apply {
                if (Build.VERSION.SDK_INT >= 29) setColors(colors, EDGE_FADE_STOPS)
            }
        }
        findViewById<View>(R.id.edge_fade_left).background = strip(true)
        findViewById<View>(R.id.edge_fade_right).background = strip(false)

        // Above and below the card band the photo gets the identical gradient baked into the
        // (cached) scrim layer, so the darkening is seamless while only the band is drawn
        // live. Solid backgrounds don't need it: the strips there match the colour exactly.
        val w = resources.getDimensionPixelSize(R.dimen.edge_fade)
        val bandTop = resources.getDimensionPixelSize(R.dimen.edge_band_top)
        val bandBottom = bandTop + resources.getDimensionPixelSize(R.dimen.edge_band_height)
        val base = getDrawable(R.drawable.bg_scrim) ?: return
        val parts = arrayOf(base, strip(true), strip(true), strip(false), strip(false))
        findViewById<View>(R.id.bg_scrim).background = android.graphics.drawable.LayerDrawable(parts).apply {
            for (i in 1..4) {
                val left = i <= 2
                val top = i % 2 == 1
                setLayerGravity(i, (if (left) android.view.Gravity.START else android.view.Gravity.END) or
                    (if (top) android.view.Gravity.TOP else android.view.Gravity.FILL_VERTICAL))
                setLayerWidth(i, w)
                if (top) setLayerHeight(i, bandTop) else setLayerInsetTop(i, bandBottom)
            }
        }
    }

    private fun setPhotoLayer(on: Boolean) {
        val layer = findViewById<View>(R.id.bg_layer)
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (layer.layerType != type) layer.setLayerType(type, null)
    }

    /**
     * Frosted backdrop for the centre card: the wallpaper patch behind it (mapped through
     * bg_image's centerCrop), box-blurred by repeated 2× bilinear downscales to ~72px wide.
     * Built once per wallpaper; null for solid-colour backgrounds.
     */
    private fun updateCardFrost(wall: Bitmap?) {
        if (!::carousel.isInitialized) return
        if (wall == null || wall.isRecycled) {
            carousel.setFrost(null)
            return
        }
        carousel.post {
            if (wall.isRecycled) return@post
            val card = carousel.centerCardOnScreen() ?: return@post
            val vw = root.width.toFloat().takeIf { it > 0f } ?: return@post
            val vh = root.height.toFloat()
            val scale = maxOf(vw / wall.width, vh / wall.height)
            val dx = (vw - wall.width * scale) / 2f
            val dy = (vh - wall.height * scale) / 2f
            val bx = ((card.left - dx) / scale).toInt().coerceIn(0, wall.width - 1)
            val by = ((card.top - dy) / scale).toInt().coerceIn(0, wall.height - 1)
            val bw = (card.width() / scale).toInt().coerceIn(1, wall.width - bx)
            val bh = (card.height() / scale).toInt().coerceIn(1, wall.height - by)
            var b = runCatching { Bitmap.createBitmap(wall, bx, by, bw, bh) }.getOrNull()
                ?: return@post
            while (b.width > 80 && b.height > 2) {
                val next = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                if (b !== wall) b.recycle()
                b = next
            }
            carousel.setFrost(b)
        }
    }

    private fun applyBackground() {
        val image = findViewById<ImageView>(R.id.bg_image)
        val scrim = findViewById<View>(R.id.bg_scrim)
        val defaultBg = prefs.solidBgs[0]
        // Photo mode keeps the old frost until the new wallpaper arrives (showWallpaper).
        val photo = prefs.bgMode == 2 && prefs.bgUri != null
        if (!photo) {
            updateCardFrost(null)
            setPhotoLayer(false)
        }
        // Cards fade into whatever the screen edge shows: the photo's dark side vignette
        // (bg_scrim, @color/bg) or the solid colour itself.
        applyEdgeFade(
            when {
                photo -> getColor(R.color.bg)
                prefs.bgMode == 1 -> prefs.solidBgs[prefs.bgIndex]
                else -> defaultBg
            }
        )

        when (prefs.bgMode) {
            0 -> {
                image.visibility = View.GONE
                scrim.visibility = View.GONE
                root.setBackgroundColor(defaultBg)
            }
            1 -> {
                image.visibility = View.GONE
                scrim.visibility = View.GONE
                val color = prefs.solidBgs[prefs.bgIndex]
                root.setBackgroundColor(color)
            }
            2 -> {
                val uriStr = prefs.bgUri
                if (uriStr == null) {
                    image.visibility = View.GONE
                    scrim.visibility = View.GONE
                    root.setBackgroundColor(defaultBg)
                    return
                }
                // Fast path: already in memory (0 ms, no flicker on resume).
                val mem = WallpaperStore.memoryDisplay(prefs)
                if (mem != null) {
                    showWallpaper(image, scrim, mem)
                    return
                }
                // Cold start / first conversion / blur toggled: run on a separate thread so
                // scanApps() is never delayed. Keep the current image until the new one is ready.
                if (image.visibility != View.VISIBLE) root.setBackgroundColor(defaultBg)
                val appCtx = applicationContext
                Thread {
                    val loaded = WallpaperStore.loadDisplay(appCtx, prefs) ?: return@Thread
                    main.post {
                        if (!isDestroyed && prefs.bgMode == 2) showWallpaper(image, scrim, loaded)
                    }
                }.start()
            }
        }
    }

    private fun scanApps(): List<AppItem> {
        val pm = packageManager
        val mine = packageName

        val leanback = query(pm, Intent.CATEGORY_LEANBACK_LAUNCHER)
        val (resolved, isLeanback) = if (leanback.isNotEmpty())
            leanback to true else query(pm, Intent.CATEGORY_LAUNCHER) to false

        val seen = HashSet<String>(resolved.size)
        return resolved
            .asSequence()
            .filter { it.activityInfo != null && it.activityInfo.packageName != mine }
            .filter { seen.add("${it.activityInfo.packageName}/${it.activityInfo.name}") }
            .map {
                val appFlags = it.activityInfo.applicationInfo?.flags ?: 0
                val isSys = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppItem(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(
                        it.activityInfo.packageName, it.activityInfo.name
                    ),
                    isLeanback = isLeanback,
                    systemApp = isSys
                )
            }
            .toList()
            .sortedBy { it.label.lowercase() }
    }

    private fun query(pm: PackageManager, category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return pm.queryIntentActivities(intent, 0) ?: emptyList()
    }

    private fun launch(item: AppItem) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (item.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER
                         else Intent.CATEGORY_LAUNCHER)
            .setComponent(item.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            packageManager.getLaunchIntentForPackage(item.component.packageName)
                ?.let { startActivity(it) }
        }
    }

    private fun openTvSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "System settings not available", Toast.LENGTH_SHORT).show()
        }
    }
}
