package com.mdev64.simpletvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern two-pane TV settings:
 * - Left sidebar: category navigation (Appearance, Clock & Date, Dock & Apps, About)
 * - Right pane: grouped cards, radio controls, true-color swatch grids with checkmarks,
 *   toggle switches with descriptions, and open-source About metadata + GitHub links.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var rootFrame: FrameLayout
    private lateinit var sidebarList: LinearLayout
    private lateinit var rightScroller: ScrollView
    private lateinit var rightContent: LinearLayout
    private lateinit var screenSaver: ScreenSaverOverlay
    private var overlay: AppMenuOverlay? = null
    private var imagePicker: ImagePickerOverlay? = null
    private var wordPromptScrim: FrameLayout? = null
    private val wordPromptPriorFocusability = ArrayList<Pair<ViewGroup, Int>>()

    private var activeTab = 0
    private var isRenderingPane = false
    private var roleRequestStartMs = 0L
    private val tabViews = ArrayList<View>(6)

    private data class TabSpec(val title: String, val subtitle: String)

    private val tabs = listOf(
        TabSpec("Appearance", "Theme & background"),
        TabSpec("Clock & Date", "Time & date format"),
        TabSpec("Screen Saver", "Idle timeout & display"),
        TabSpec("Pinned & Apps", "Top pins & hidden apps"),
        TabSpec("Default Launcher", "Home button & startup"),
        TabSpec("About", "Version & open source")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setTitle(R.string.settings_title)

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_ROOT)
        }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), dp(28), dp(40), dp(28))
        }

        // ---- Left Sidebar ----
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(24), 0)
        }

        sidebar.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(TEXT_PRIMARY)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })

        sidebar.addView(TextView(this).apply {
            text = "Simple TV Launcher"
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
            setPadding(dp(12), dp(2), 0, dp(20))
        })

        sidebarList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sidebar.addView(sidebarList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        mainRow.addView(sidebar, LinearLayout.LayoutParams(
            dp(260), ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Subtle vertical divider between sidebar and right pane
        mainRow.addView(View(this).apply {
            setBackgroundColor(0x1AFFFFFF)
        }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        // ---- Right Content Pane ----
        rightContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(4), dp(12), dp(48))
        }

        rightScroller = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isVerticalScrollBarEnabled = false
            addView(rightContent, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        mainRow.addView(rightScroller, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ))

        rootFrame.addView(mainRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootFrame)
        screenSaver = ScreenSaverOverlay(this, rootFrame, prefs)
        buildSidebar()
        renderRightPane(restoreTag = null, focusRightPane = false)
        tabViews.firstOrNull()?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        DefaultLauncherHelper.rememberPreviousLauncherIfNeeded(this, prefs)
        if (DefaultLauncherHelper.isDefaultHome(this)) {
            prefs.defaultLauncherEnabled = true
        }
        if (activeTab == 4 && wordPromptScrim == null && overlay?.isShowing != true) {
            renderRightPane(
                restoreTag = findFocusedTag(rightContent),
                focusRightPane = hasFocusedChild(rightContent)
            )
        }
        screenSaver.resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        screenSaver.stopIdleTimer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screenSaver.onKeyEvent(event)) return true
        imagePicker?.takeIf { it.isShowing }?.let { picker ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) picker.handleBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (paletteScrim != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                dismissColorPalette(restore = false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (wordPromptScrim != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                dismissCustomWordPrompt()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val menu = overlay
        if (menu != null && menu.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                menu.dismiss()
                overlay = null
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (hasFocusedChild(rightContent)) {
                tabViews.getOrNull(activeTab)?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (paletteScrim != null) {
            dismissColorPalette(restore = false)
            return
        }
        if (wordPromptScrim != null) {
            dismissCustomWordPrompt()
            return
        }
        val menu = overlay
        if (menu != null && menu.isShowing) {
            menu.dismiss()
            overlay = null
            return
        }
        if (hasFocusedChild(rightContent)) {
            tabViews.getOrNull(activeTab)?.requestFocus()
            return
        }
        super.onBackPressed()
    }

    // -------------------------------------------------------------- sidebar

    private fun buildSidebar() {
        sidebarList.removeAllViews()
        tabViews.clear()

        tabs.forEachIndexed { index, spec ->
            val tab = createTabView(index, spec)
            tab.id = View.generateViewId()
            tabViews.add(tab)
            sidebarList.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            })
        }

        for (i in tabViews.indices) {
            val v = tabViews[i]
            v.nextFocusUpId = if (i > 0) tabViews[i - 1].id else v.id
            v.nextFocusDownId = if (i < tabViews.size - 1) tabViews[i + 1].id else v.id
            v.nextFocusLeftId = v.id
        }
        updateSidebarStyles()
    }

    private fun createTabView(index: Int, spec: TabSpec): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }

        val indicator = View(this).apply {
            tag = "indicator"
        }
        container.addView(indicator, LinearLayout.LayoutParams(dp(4), dp(26)).apply {
            marginEnd = dp(12)
        })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(this).apply {
            tag = "title"
            text = spec.title
            textSize = 16f
            setSingleLine(true)
        }
        val subView = TextView(this).apply {
            tag = "sub"
            text = spec.subtitle
            textSize = 12f
            setSingleLine(true)
        }
        textCol.addView(titleView)
        textCol.addView(subView)
        container.addView(textCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        container.setOnFocusChangeListener { _, hasFocus ->
            if (isRenderingPane) return@setOnFocusChangeListener
            if (hasFocus && activeTab != index) {
                activeTab = index
                rightScroller.scrollTo(0, 0)
                renderRightPane(restoreTag = null, focusRightPane = false, preserveScroll = false)
            }
            updateSidebarStyles()
        }

        container.setOnClickListener {
            activeTab = index
            updateSidebarStyles()
            val firstRight = firstFocusableIn(rightContent)
            firstRight?.requestFocus()
        }

        return container
    }

    private fun updateSidebarStyles() {
        val accent = prefs.accent
        tabViews.forEachIndexed { idx, view ->
            val selected = idx == activeTab
            val focused = view.isFocused
            val bg = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                when {
                    focused -> {
                        setColor(blend(CARD_BG, accent, 0.28f))
                        setStroke(dp(2), accent)
                    }
                    selected -> {
                        setColor(0x1FFFFFFF)
                        setStroke(dp(1), 0x26FFFFFF)
                    }
                    else -> {
                        setColor(0x00000000)
                    }
                }
            }
            view.background = bg

            val indicator = view.findViewWithTag<View>("indicator")
            indicator?.background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(if (selected || focused) accent else 0x00000000)
            }

            val title = view.findViewWithTag<TextView>("title")
            title?.setTextColor(if (selected || focused) TEXT_PRIMARY else TEXT_SECONDARY)
            title?.setTypeface(title.typeface, if (selected || focused) Typeface.BOLD else Typeface.NORMAL)

            val sub = view.findViewWithTag<TextView>("sub")
            sub?.setTextColor(if (focused) TEXT_PRIMARY else TEXT_MUTED)
        }
    }

    // ------------------------------------------------------------ right pane

    private fun renderRightPane(
        restoreTag: String?,
        focusRightPane: Boolean,
        preserveScroll: Boolean = true
    ) {
        val currentFocusedTag = restoreTag ?: findFocusedTag(rightContent)
        val wasInRightPane = focusRightPane || hasFocusedChild(rightContent)
        val savedScrollY = if (preserveScroll) rightScroller.scrollY else 0
        val targetTab = activeTab

        isRenderingPane = true
        val prevSidebarFocusability = sidebarList.descendantFocusability
        if (wasInRightPane) {
            // Prevent removeAllViews() from kicking focus back to tabViews[0] in sidebar
            sidebarList.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rightScroller.isFocusable = true
            rightScroller.isFocusableInTouchMode = true
            rightScroller.requestFocus()
        }

        rightContent.removeAllViews()
        activeTab = targetTab

        when (activeTab) {
            0 -> buildAppearancePane()
            1 -> buildClockPane()
            2 -> buildScreenSaverPane()
            3 -> buildDockAndAppsPane()
            4 -> buildDefaultLauncherPane()
            5 -> buildAboutPane()
        }

        wireFocus()

        sidebarList.descendantFocusability = prevSidebarFocusability

        if (wasInRightPane) {
            val target = (currentFocusedTag?.let { findViewByTagRecursive(rightContent, it) }
                ?: firstFocusableIn(rightContent))
            if (target != null) {
                target.requestFocus()
            } else {
                tabViews.getOrNull(activeTab)?.requestFocus()
            }
            rightScroller.isFocusable = false
            rightScroller.isFocusableInTouchMode = false
            if (preserveScroll) {
                rightScroller.scrollTo(0, savedScrollY)
                rightScroller.post {
                    rightScroller.scrollTo(0, savedScrollY)
                }
            }
        }

        isRenderingPane = false
        updateSidebarStyles()
    }

    // ---- Tab 0: Appearance ------------------------------------------------

    private fun buildAppearancePane() {
        paneHeader("Appearance", "Customize the launcher's background and accent color.")

        sectionHeader("BACKGROUND")
        optionRow(
            tag = "bg_mode:1",
            title = "Solid Color",
            subtitle = "Choose from 40 curated television-safe color tones (default: black)",
            radioSelected = prefs.bgMode == 1
        ) {
            prefs.bgMode = 1
            renderRightPane(restoreTag = "bg_mode:1", focusRightPane = true)
        }

        if (prefs.bgMode == 1) {
            optionRow(
                tag = "bg_color:open",
                title = "Background Color",
                subtitle = "Open the color palette to change the solid background",
                badgeText = "Choose…",
                swatchColor = prefs.solidBgs[prefs.bgIndex]
            ) {
                showColorPalette(
                    restoreTag = "bg_color:open",
                    title = "Background Color",
                    colors = prefs.solidBgs,
                    perRow = 8,
                    selectedIndex = prefs.bgIndex
                ) { i -> prefs.bgIndex = i }
            }
        }

        optionRow(
            tag = "bg_mode:2",
            title = "Custom Photo",
            subtitle = "Use a photo from device storage as the home background",
            radioSelected = prefs.bgMode == 2
        ) {
            // Photo mode only activates once a photo is actually chosen.
            pickWallpaper()
        }

        if (prefs.bgMode == 2) {
            optionRow(
                tag = "bg_action:pick",
                title = "Change Wallpaper Image…",
                subtitle = "Pre-scaled 1080p wallpaper active",
                badgeText = "Browse"
            ) {
                pickWallpaper()
            }

            optionRow(
                tag = "bg_blur",
                title = "Blur Background",
                subtitle = "Soften the wallpaper behind the app grid",
                toggleValue = prefs.bgBlur
            ) {
                prefs.bgBlur = !prefs.bgBlur
                renderRightPane(restoreTag = "bg_blur", focusRightPane = true)
            }

            optionRow(
                tag = "bg_action:remove",
                title = "Remove Wallpaper",
                subtitle = "Clear custom image and revert to solid black",
                badgeText = "Remove",
                destructive = true
            ) {
                confirm(
                    title = "Remove wallpaper?",
                    message = "The selected photo will be removed and the background will return to solid black.",
                    confirmLabel = "Yes, remove photo"
                ) {
                    resetToSolidBlack()
                    renderRightPane(restoreTag = "bg_mode:1", focusRightPane = true)
                }
            }
        }

        sectionHeader("ACCENT COLOR")
        optionRow(
            tag = "theme:open",
            title = "Accent Color",
            subtitle = "Open the color palette to change the launcher accent",
            badgeText = "Choose…",
            swatchColor = prefs.accent
        ) {
            showColorPalette(
                restoreTag = "theme:open",
                title = "Accent Color",
                colors = prefs.palette,
                perRow = 6,
                selectedIndex = prefs.themeIndex
            ) { i -> prefs.themeIndex = i }
        }
    }

    // ---- Tab 1: Clock & Date ----------------------------------------------

    private fun buildClockPane() {
        paneHeader("Clock & Date", "Configure the top-left time and date display on the home screen.")

        sectionHeader("PREVIEW")
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(CARD_BG)
                setStroke(dp(1), 0x22FFFFFF)
            }
        }
        val now = Date()
        val timeStr = SimpleDateFormat(
            if (prefs.clock24h) "HH:mm" else "h:mm a", Locale.getDefault()
        ).format(now)
        val dateStr = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)

        if (prefs.clockTime) {
            previewCard.addView(TextView(this).apply {
                text = timeStr
                setTextColor(TEXT_PRIMARY)
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        if (prefs.clockDate) {
            previewCard.addView(TextView(this).apply {
                text = dateStr
                setTextColor(TEXT_PRIMARY)
                textSize = 13f
            })
        }
        if (!prefs.clockTime && !prefs.clockDate) {
            previewCard.addView(TextView(this).apply {
                text = "Clock and date hidden on home screen"
                setTextColor(TEXT_SECONDARY)
                textSize = 14f
            })
        }
        rightContent.addView(previewCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(8) })

        sectionHeader("FORMAT & VISIBILITY")
        optionRow(
            tag = "clock:time",
            title = "Show Time",
            subtitle = "Display the digital clock in the top-left corner",
            toggleValue = prefs.clockTime
        ) {
            prefs.clockTime = !prefs.clockTime
            renderRightPane(restoreTag = "clock:time", focusRightPane = true)
        }

        optionRow(
            tag = "clock:date",
            title = "Show Date",
            subtitle = "Display the current day and date underneath the clock",
            toggleValue = prefs.clockDate
        ) {
            prefs.clockDate = !prefs.clockDate
            renderRightPane(restoreTag = "clock:date", focusRightPane = true)
        }

        optionRow(
            tag = "clock:24h",
            title = "24-Hour Clock",
            subtitle = "Use 24-hour format (19:30) instead of 12-hour AM/PM",
            toggleValue = prefs.clock24h
        ) {
            prefs.clock24h = !prefs.clock24h
            renderRightPane(restoreTag = "clock:24h", focusRightPane = true)
        }
    }

    // ---- Tab 2: Screen Saver ----------------------------------------------

    private fun buildScreenSaverPane() {
        paneHeader(
            "Screen Saver",
            "Choose an idle timeout and what happens when the launcher stays inactive."
        )

        sectionHeader("WHEN IDLE")
        optionRow(
            tag = "ss_mode:0",
            title = "Do Nothing",
            subtitle = "Keep the home screen active without starting a screen saver",
            radioSelected = prefs.screensaverMode == ScreenSaverOverlay.MODE_NONE
        ) {
            prefs.screensaverMode = ScreenSaverOverlay.MODE_NONE
            screenSaver.resetIdleTimer()
            renderRightPane(restoreTag = "ss_mode:0", focusRightPane = true)
        }

        optionRow(
            tag = "ss_mode:1",
            title = "Full Black Screen",
            subtitle = "Turn the display completely black to save power and protect OLED panels",
            radioSelected = prefs.screensaverMode == ScreenSaverOverlay.MODE_BLACK
        ) {
            prefs.screensaverMode = ScreenSaverOverlay.MODE_BLACK
            screenSaver.resetIdleTimer()
            renderRightPane(restoreTag = "ss_mode:1", focusRightPane = true)
        }

        optionRow(
            tag = "ss_mode:2",
            title = "Black Screen & Display Time",
            subtitle = "Turn the screen full black and display the current time centered on screen",
            radioSelected = prefs.screensaverMode == ScreenSaverOverlay.MODE_CLOCK
        ) {
            prefs.screensaverMode = ScreenSaverOverlay.MODE_CLOCK
            screenSaver.resetIdleTimer()
            renderRightPane(restoreTag = "ss_mode:2", focusRightPane = true)
        }

        optionRow(
            tag = "ss_mode:3",
            title = "Old-School DVD Bounce",
            subtitle = "Retro DVD player style animation bouncing your custom word corner to corner",
            radioSelected = prefs.screensaverMode == ScreenSaverOverlay.MODE_DVD
        ) {
            prefs.screensaverMode = ScreenSaverOverlay.MODE_DVD
            screenSaver.resetIdleTimer()
            renderRightPane(restoreTag = "ss_mode:3", focusRightPane = true)
        }

        if (prefs.screensaverMode != ScreenSaverOverlay.MODE_NONE) {
            sectionHeader("IDLE TIMEOUT (MINUTES)")
            timeoutGrid(
                options = prefs.timeoutOptions,
                selectedMinutes = prefs.screensaverTimeoutMin
            ) { minutes ->
                prefs.screensaverTimeoutMin = minutes
                screenSaver.resetIdleTimer()
                renderRightPane(restoreTag = "ss_timeout:$minutes", focusRightPane = true)
            }

            if (prefs.screensaverMode == ScreenSaverOverlay.MODE_DVD) {
                sectionHeader("DVD BOUNCE WORD")
                optionRow(
                    tag = "ss_word:edit",
                    title = "Custom Bouncing Word",
                    subtitle = "Current word: “${prefs.screensaverText}” — select to type your own word",
                    badgeText = "Edit Word…"
                ) {
                    showCustomWordPrompt()
                }
            }

            sectionHeader("PREVIEW")
            val previewMode = prefs.screensaverMode
            val previewLabel = when (previewMode) {
                ScreenSaverOverlay.MODE_BLACK -> "Full Black Screen"
                ScreenSaverOverlay.MODE_CLOCK -> "Black Screen & Time"
                else -> "DVD Bounce (“${prefs.screensaverText}”)"
            }
            optionRow(
                tag = "ss_preview",
                title = "Preview Screen Saver",
                subtitle = "Test $previewLabel now (press any remote button to exit)",
                badgeText = "Start Now"
            ) {
                screenSaver.start(previewMode)
            }
        }
    }

    private fun timeoutGrid(
        options: IntArray,
        selectedMinutes: Int,
        onPick: (Int) -> Unit
    ) {
        val accent = prefs.accent
        val onAccent = if (isLightColor(accent)) 0xFF121218.toInt() else 0xFFFFFFFF.toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(CARD_BG)
                setStroke(dp(1), 0x1FFFFFFF)
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
        }

        val pillViews = ArrayList<View>(options.size)
        options.forEach { minutes ->
            val selected = minutes == selectedMinutes
            val radius = dp(12).toFloat()
            fun pillBg(focused: Boolean): Drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                when {
                    focused -> {
                        setColor(accent)
                        setStroke(dp(2), 0xFFFFFFFF.toInt())
                    }
                    selected -> {
                        setColor(blend(CARD_BG, accent, 0.26f))
                        setStroke(dp(2), accent)
                    }
                    else -> {
                        setColor(0x16FFFFFF)
                        setStroke(dp(1), 0x26FFFFFF)
                    }
                }
            }

            val pill = TextView(this).apply {
                id = View.generateViewId()
                tag = "ss_timeout:$minutes"
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                gravity = Gravity.CENTER
                text = if (selected) "✓ $minutes min" else "$minutes min"
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) accent else TEXT_PRIMARY)
                background = pillBg(false)
                setOnClickListener { onPick(minutes) }
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = pillBg(hasFocus)
                    (v as TextView).setTextColor(
                        when {
                            hasFocus -> onAccent
                            selected -> accent
                            else -> TEXT_PRIMARY
                        }
                    )
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                        .scaleY(if (hasFocus) 1.06f else 1f)
                        .setDuration(110).start()
                    if (hasFocus && !isRenderingPane) v.post { scrollIntoView(v) }
                }
            }
            pillViews.add(pill)
            row.addView(pill, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }

        val activeTabId = tabViews.getOrNull(activeTab)?.id ?: View.NO_ID
        pillViews.forEachIndexed { idx, v ->
            v.nextFocusLeftId = if (idx > 0) pillViews[idx - 1].id else activeTabId
            v.nextFocusRightId = if (idx < pillViews.size - 1) pillViews[idx + 1].id else v.id
        }

        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        rightContent.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) })
    }

    private fun showCustomWordPrompt() {
        dismissCustomWordPrompt()

        wordPromptPriorFocusability.clear()
        for (i in 0 until rootFrame.childCount) {
            val child = rootFrame.getChildAt(i)
            if (child is ViewGroup) {
                wordPromptPriorFocusability.add(child to child.descendantFocusability)
                child.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }

        val accent = prefs.accent
        val onAccent = if (isLightColor(accent)) 0xFF141418.toInt() else 0xFFFFFFFF.toInt()

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB805050A.toInt())
            isClickable = true
            isFocusable = false
        }

        val dialog = GlassLayout(this).apply {
            radiusDp = 26f
            applyAdaptiveTint(CARD_BG, accent)
            isClickable = true
            isFocusable = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            isFocusable = false
        }

        content.addView(TextView(this).apply {
            text = "Custom Bouncing Word"
            setTextColor(TEXT_PRIMARY)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "Type the word or short phrase to bounce corner to corner"
            setTextColor(TEXT_SECONDARY)
            textSize = 12.5f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(14) })

        fun inputBg(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (focused) 0x26FFFFFF else 0x18FFFFFF)
            setStroke(dp(if (focused) 2 else 1), if (focused) accent else 0x38FFFFFF)
        }

        val editInput = EditText(this).apply {
            id = View.generateViewId()
            setText(prefs.screensaverText)
            setSelection(text.length)
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            hint = "DVD"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(24))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = inputBg(false)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, hasFocus ->
                v.background = inputBg(hasFocus)
            }
        }
        content.addView(editInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        fun buildModalRow(label: String, badge: String, onClick: () -> Unit): LinearLayout {
            val idleRowBg = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x16FFFFFF)
            }
            val focusRowBg = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(accent)
            }
            val titleView = TextView(this).apply {
                text = label
                setTextColor(TEXT_PRIMARY)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            val badgeView = TextView(this).apply {
                text = badge
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            }
            return LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(16), dp(11))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = idleRowBg
                addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(badgeView)
                setOnClickListener { onClick() }
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) focusRowBg else idleRowBg
                    titleView.setTextColor(if (hasFocus) onAccent else TEXT_PRIMARY)
                    badgeView.setTextColor(if (hasFocus) onAccent else TEXT_SECONDARY)
                }
            }
        }

        val saveAction = {
            val entered = editInput.text?.toString()?.trim().orEmpty()
            prefs.screensaverText = entered.ifEmpty { "DVD" }
            dismissCustomWordPrompt()
            renderRightPane(restoreTag = "ss_word:edit", focusRightPane = true)
        }

        val saveBtn = buildModalRow("Save Word", "OK", saveAction)
        val cancelBtn = buildModalRow("Cancel", "ESC") {
            dismissCustomWordPrompt()
        }

        content.addView(saveBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        content.addView(cancelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Explicit D-pad focus chain inside modal
        editInput.nextFocusUpId = editInput.id
        editInput.nextFocusDownId = saveBtn.id
        editInput.nextFocusLeftId = editInput.id
        editInput.nextFocusRightId = editInput.id

        saveBtn.nextFocusUpId = editInput.id
        saveBtn.nextFocusDownId = cancelBtn.id
        saveBtn.nextFocusLeftId = saveBtn.id
        saveBtn.nextFocusRightId = saveBtn.id

        cancelBtn.nextFocusUpId = saveBtn.id
        cancelBtn.nextFocusDownId = cancelBtn.id
        cancelBtn.nextFocusLeftId = cancelBtn.id
        cancelBtn.nextFocusRightId = cancelBtn.id

        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                saveAction()
                true
            } else {
                false
            }
        }

        editInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                saveBtn.requestFocus()
                true
            } else {
                false
            }
        }

        dialog.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scrim.addView(dialog, FrameLayout.LayoutParams(
            dp(400),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        rootFrame.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        wordPromptScrim = scrim

        editInput.requestFocus()
        editInput.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dismissCustomWordPrompt() {
        val scrim = wordPromptScrim ?: return
        wordPromptScrim = null

        currentFocus?.let { focused ->
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
        }

        isRenderingPane = true
        wordPromptPriorFocusability.forEach { (vg, mode) -> vg.descendantFocusability = mode }
        wordPromptPriorFocusability.clear()

        findViewByTagRecursive(rightContent, "ss_word:edit")?.requestFocus()
        rootFrame.removeView(scrim)
        isRenderingPane = false
        updateSidebarStyles()
    }

    // ---- Tab 3: Pinned & Apps ---------------------------------------------

    private fun buildDockAndAppsPane() {
        paneHeader("Pinned & Apps", "Manage the apps on your home carousel and restore hidden applications.")

        val dockCount = prefs.pins().size
        sectionHeader("HOME CAROUSEL")
        infoCard(
            title = "Pinned Apps",
            subtitle = "Hold OK on an app in Other apps to add it to home; hold OK on a home card to move or remove it",
            value = "$dockCount pinned"
        )

        if (dockCount > 0) {
            optionRow(
                tag = "dock:reset",
                title = "Reset Pinned Apps",
                subtitle = "Remove all $dockCount apps from the home carousel (they return to Other apps)",
                badgeText = "Reset",
                destructive = true
            ) {
                confirmResetDock()
            }
        }

        sectionHeader("HIDDEN APPS")
        val hidden = pruneStale(prefs.hidden()).toList()
        if (hidden.isEmpty()) {
            infoCard(
                title = "No Hidden Apps",
                subtitle = "Hold OK on any app and select “Hide app” to hide it",
                value = "0 hidden"
            )
        } else {
            hidden.forEachIndexed { idx, key ->
                val pkg = key.substringBefore("/")
                optionRow(
                    tag = "hidden:$idx",
                    title = labelFor(key),
                    subtitle = pkg,
                    badgeText = "Unhide"
                ) {
                    prefs.unhide(key)
                    renderRightPane(restoreTag = "hidden:$idx", focusRightPane = true)
                }
            }
            optionRow(
                tag = "hidden:all",
                title = "Unhide All Apps",
                subtitle = "Restore all ${hidden.size} hidden apps back to Other apps",
                badgeText = "Restore All",
                destructive = true
            ) {
                confirmUnhideAll(hidden.size)
            }
        }
    }

    // ---- Tab 4: Default Launcher ------------------------------------------

    private fun buildDefaultLauncherPane() {
        paneHeader(
            "Default Launcher",
            "Replace the current TV launcher, take over the remote Home button, or switch back to another installed launcher."
        )

        val isDefault = DefaultLauncherHelper.isDefaultHome(this)
        val currentHolder = DefaultLauncherHelper.currentDefaultHome(this)
        val prevLauncher = DefaultLauncherHelper.getPreviousLauncher(this, prefs)
        val enabledOthers = DefaultLauncherHelper.otherInstalledLaunchers(this)
        val disabledStock = DefaultLauncherHelper.disabledSystemLaunchers(this)
        val currentLabel = currentHolder?.label ?: prevLauncher?.label ?: "System Launcher"

        sectionHeader("CURRENT HOME STATUS")
        infoCard(
            title = if (isDefault) {
                "Simple TV Launcher is Your Default"
            } else {
                "Current Default: $currentLabel"
            },
            subtitle = if (isDefault) {
                "Pressing the TV remote Home button and restarting the TV open Simple TV Launcher"
            } else {
                "Pressing the TV remote Home button currently opens $currentLabel"
            },
            value = if (isDefault) "Active ✓" else "Not Default"
        )

        if (!isDefault) {
            sectionHeader("HOME BUTTON & STARTUP")
            optionRow(
                tag = "home:make_default",
                title = "Set as Default Launcher",
                subtitle = "Replace $currentLabel and take over the TV remote Home button",
                badgeText = "Make Default"
            ) {
                DefaultLauncherHelper.requestMakeDefaultLauncher(
                    activity = this,
                    prefs = prefs,
                    requestCode = REQ_DEFAULT_HOME
                ) {
                    roleRequestStartMs = SystemClock.elapsedRealtime()
                }
            }
        } else {
            if (enabledOthers.isNotEmpty()) {
                sectionHeader("UNDO & RESTORE LAUNCHER")
                val ordered = if (prevLauncher != null) {
                    enabledOthers.sortedByDescending { it.packageName == prevLauncher.packageName }
                } else {
                    enabledOthers
                }
                ordered.forEachIndexed { idx, candidate ->
                    val isPrimary = idx == 0
                    optionRow(
                        tag = "home:restore:${candidate.packageName}",
                        title = if (isPrimary) {
                            "Undo & Return to ${candidate.label}"
                        } else {
                            "Switch to ${candidate.label}"
                        },
                        subtitle = "Release the TV remote Home button and make ${candidate.label} your default launcher",
                        badgeText = if (isPrimary) "Restore" else "Switch",
                        destructive = isPrimary
                    ) {
                        confirm(
                            title = "Switch to ${candidate.label}?",
                            message = "Simple TV Launcher will release the Home button and switch to ${candidate.label}.",
                            confirmLabel = "Yes, switch to ${candidate.label}"
                        ) {
                            DefaultLauncherHelper.restorePreviousLauncher(this, prefs, candidate)
                        }
                    }
                }
            }

            if (disabledStock.isNotEmpty()) {
                sectionHeader(
                    if (enabledOthers.isEmpty()) "UNDO & RESTORE STOCK LAUNCHER"
                    else "DISABLED LAUNCHERS"
                )
                disabledStock.forEach { stock ->
                    optionRow(
                        tag = "home:enable_stock:${stock.packageName}",
                        title = "Enable ${stock.label} (Stock Launcher)",
                        subtitle = if (enabledOthers.isEmpty()) {
                            "No other active launcher found — enable ${stock.label} in App Info before switching back"
                        } else {
                            "${stock.label} is currently disabled on this TV — select to open App Info"
                        },
                        badgeText = "Enable ↗"
                    ) {
                        DefaultLauncherHelper.openLauncherAppDetails(this, stock.packageName)
                    }
                }
            }

            if (enabledOthers.isEmpty() && disabledStock.isEmpty()) {
                sectionHeader("OTHER LAUNCHERS")
                infoCard(
                    title = "No Other Launcher Installed",
                    subtitle = "Simple TV Launcher is currently the only home launcher available on this TV",
                    value = "Only Home App"
                )
            }
        }
    }

    // ---- Tab 5: About -----------------------------------------------------

    private fun buildAboutPane() {
        paneHeader("About", "Application information, architecture details, and open-source links.")

        // Hero summary card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(CARD_BG)
                setStroke(dp(1), 0x26FFFFFF)
            }
        }

        val badgeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1B1B25.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            clipToOutline = true
        }
        hero.addView(badgeIcon, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
            marginEnd = dp(18)
        })

        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        heroText.addView(TextView(this).apply {
            text = "Simple TV Launcher"
            setTextColor(TEXT_PRIMARY)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        heroText.addView(TextView(this).apply {
            text = "Fast, minimal, open-source Android TV home screen"
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
        })
        hero.addView(heroText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        rightContent.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(8) })

        sectionHeader("BUILD & SYSTEM INFO")
        metadataGroupCard(
            listOf(
                "App Version" to appVersion(),
                "Package Name" to packageName,
                "UI Engine" to "Native Platform Views (Zero Compose / Leanback)",
                "Memory in Use" to memoryInUse(),
                "APK Size" to apkSize(),
                "License" to "MIT Open Source License"
            )
        )

        sectionHeader("OPEN SOURCE")
        optionRow(
            tag = "about:github",
            title = "GitHub Repository",
            subtitle = "https://github.com/mdev64/simple-tv-launcher",
            badgeText = "Open ↗"
        ) {
            openUrl(
                title = "GitHub Repository",
                url = "https://github.com/mdev64/simple-tv-launcher"
            )
        }

        optionRow(
            tag = "about:issues",
            title = "Report an Issue / Source Releases",
            subtitle = "https://github.com/mdev64/simple-tv-launcher/releases",
            badgeText = "Open ↗"
        ) {
            openUrl(
                title = "Releases & Issue Tracker",
                url = "https://github.com/mdev64/simple-tv-launcher/releases"
            )
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (_: Exception) { "—" }

    /** Live PSS of this process (home screen + settings share it), as `adb dumpsys meminfo` reports. */
    private fun memoryInUse(): String = try {
        val mi = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(mi)
        String.format(Locale.US, "%.0f MB (live)", mi.totalPss / 1024f)
    } catch (_: Exception) { "—" }

    private fun apkSize(): String = try {
        val bytes = java.io.File(applicationInfo.sourceDir).length()
        String.format(Locale.US, "%.0f KB", bytes / 1024f)
    } catch (_: Exception) { "—" }

    private fun openUrl(title: String, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            confirm(
                title = title,
                message = url,
                confirmLabel = "OK"
            ) { }
        }
    }

    // -------------------------------------------------------- UI components

    private fun paneHeader(title: String, subtitle: String) {
        rightContent.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        rightContent.addView(TextView(this).apply {
            text = subtitle
            setTextColor(TEXT_SECONDARY)
            textSize = 13f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(8) })
    }

    private fun sectionHeader(text: String) {
        rightContent.addView(TextView(this).apply {
            this.text = text
            setTextColor(prefs.accent)
            textSize = 12f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18); bottomMargin = dp(6) })
    }

    /**
     * Interactive settings card row with title, subtitle, and one right-hand
     * control: a radio indicator, a toggle switch, or an action badge.
     */
    private fun optionRow(
        tag: String,
        title: String,
        subtitle: String,
        radioSelected: Boolean? = null,
        toggleValue: Boolean? = null,
        badgeText: String? = null,
        destructive: Boolean = false,
        swatchColor: Int? = null,
        onClick: () -> Unit
    ) {
        val accent = prefs.accent
        val activeColor = if (destructive) COLOR_DANGER else accent

        val idleBg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(CARD_BG)
            setStroke(dp(1), 0x1FFFFFFF)
        }
        val focusedBg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(blend(CARD_BG, activeColor, 0.28f))
            setStroke(dp(2), activeColor)
        }

        val row = LinearLayout(this).apply {
            this.tag = tag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = idleBg
            contentDescription = title
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focusedBg else idleBg
                if (hasFocus && !isRenderingPane) v.post { scrollIntoView(v) }
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(if (destructive) COLOR_DANGER else TEXT_PRIMARY)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(TEXT_SECONDARY)
            textSize = 12.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        row.addView(textCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(16) })

        if (radioSelected != null) {
            row.addView(radioIndicator(radioSelected, accent),
                LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        if (toggleValue != null) {
            row.addView(toggleSwitch(toggleValue, accent),
                LinearLayout.LayoutParams(dp(48), dp(26)))
        }
        if (swatchColor != null) {
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(swatchColor)
                    setStroke(dp(2), 0x66FFFFFF)
                }
            }, LinearLayout.LayoutParams(dp(44), dp(28)).apply { marginEnd = dp(10) })
        }
        if (badgeText != null) {
            row.addView(TextView(this).apply {
                text = badgeText
                setTextColor(if (destructive) COLOR_DANGER else TEXT_PRIMARY)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (destructive) 0x26FF453A else 0x22FFFFFF)
                    setStroke(dp(1), if (destructive) COLOR_DANGER else 0x33FFFFFF)
                }
            })
        }

        rightContent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    /** Non-focusable read-only summary card so info rows never masquerade as buttons. */
    private fun infoCard(title: String, subtitle: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x12FFFFFF)
                setStroke(dp(1), 0x18FFFFFF)
            }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(TEXT_SECONDARY)
            textSize = 12.5f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        row.addView(textCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(16) })

        row.addView(TextView(this).apply {
            text = value
            setTextColor(prefs.accent)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x1AFFFFFF)
            }
        })

        rightContent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    /** Grouped read-only key/value table card for the About tab. */
    private fun metadataGroupCard(items: List<Pair<String, String>>) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(CARD_BG)
                setStroke(dp(1), 0x1FFFFFFF)
            }
        }
        items.forEachIndexed { idx, (k, v) ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            line.addView(TextView(this).apply {
                text = k
                setTextColor(TEXT_SECONDARY)
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            line.addView(TextView(this).apply {
                text = v
                setTextColor(TEXT_PRIMARY)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(line)

            if (idx < items.size - 1) {
                card.addView(View(this).apply {
                    setBackgroundColor(0x14FFFFFF)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ))
            }
        }
        rightContent.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) })
    }

    /** Proper radio button: outer ring + inner filled circle when selected. */
    private fun radioIndicator(selected: Boolean, accent: Int): View {
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val stroke = dp(2).toFloat()
                val r = (width.coerceAtMost(height) / 2f) - stroke
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = if (selected) accent else 0x66FFFFFF
                canvas.drawCircle(cx, cy, r, paint)
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = accent
                    canvas.drawCircle(cx, cy, r * 0.52f, paint)
                }
            }
        }
    }

    private fun toggleSwitch(on: Boolean, accent: Int): View {
        val track = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(if (on) accent else 0x38FFFFFF)
        }
        val thumbColor = if (on && isLightColor(accent)) 0xFF141418.toInt() else 0xFFFFFFFF.toInt()
        val thumb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(thumbColor)
            }
        }
        return FrameLayout(this).apply {
            background = track
            addView(thumb, FrameLayout.LayoutParams(dp(20), dp(20),
                if (on) Gravity.END or Gravity.CENTER_VERTICAL
                else Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
    }

    // ---- Color palette popup ---------------------------------------------

    private var paletteScrim: FrameLayout? = null
    private var paletteRestoreTag: String? = null
    private val palettePriorFocusability = ArrayList<Pair<ViewGroup, Int>>()

    /** Opens the full swatch grid in an overlay with the current color pre-focused. */
    private fun showColorPalette(
        restoreTag: String,
        title: String,
        colors: IntArray,
        perRow: Int,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        dismissColorPalette(restore = false)
        paletteRestoreTag = restoreTag

        palettePriorFocusability.clear()
        for (i in 0 until rootFrame.childCount) {
            val child = rootFrame.getChildAt(i)
            if (child is ViewGroup) {
                palettePriorFocusability.add(child to child.descendantFocusability)
                child.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB805050A.toInt())
            isClickable = true
            isFocusable = false
        }
        val card = GlassLayout(this).apply {
            radiusDp = 26f
            applyAdaptiveTint(CARD_BG, prefs.accent)
            isFocusable = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        content.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(10))
        })

        val rows = ArrayList<ArrayList<View>>()
        var i = 0
        while (i < colors.size) {
            val rowViews = ArrayList<View>()
            while (i < colors.size && rowViews.size < perRow) {
                val index = i
                val v = swatch(
                    tag = "palette:$index",
                    color = colors[index],
                    selected = index == selectedIndex
                ) {
                    onPick(index)
                    dismissColorPalette(restore = true)
                }
                v.id = View.generateViewId()
                rowViews.add(v)
                i++
            }
            rows.add(rowViews)
        }
        rows.forEach { rowViews ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowViews.forEach { v ->
                row.addView(v, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            content.addView(row)
        }
        rows.forEachIndexed { r, rowViews ->
            rowViews.forEachIndexed { c, v ->
                v.nextFocusLeftId = if (c > 0) rowViews[c - 1].id else v.id
                v.nextFocusRightId = if (c < rowViews.size - 1) rowViews[c + 1].id else v.id
                v.nextFocusUpId = if (r > 0 && c < rows[r - 1].size) rows[r - 1][c].id else v.id
                v.nextFocusDownId = if (r < rows.size - 1 && c < rows[r + 1].size) rows[r + 1][c].id else v.id
            }
        }

        card.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrim.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        rootFrame.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        paletteScrim = scrim

        val initial = rows.flatten().getOrNull(selectedIndex) ?: rows.firstOrNull()?.firstOrNull()
        initial?.post { initial.requestFocus() }
    }

    private fun dismissColorPalette(restore: Boolean) {
        val scrim = paletteScrim ?: return
        paletteScrim = null
        palettePriorFocusability.forEach { (vg, mode) -> vg.descendantFocusability = mode }
        palettePriorFocusability.clear()
        rootFrame.removeView(scrim)
        if (restore) {
            renderRightPane(restoreTag = paletteRestoreTag, focusRightPane = true)
        } else {
            paletteRestoreTag?.let { findViewByTagRecursive(rightContent, it)?.requestFocus() }
        }
    }

    // ---- Swatch Grid (true color + checkmark + crisp focus ring) ---------

    private fun swatchGrid(
        prefix: String,
        colors: IntArray,
        perRow: Int,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(CARD_BG)
                setStroke(dp(1), 0x1FFFFFFF)
            }
        }

        val grid = ArrayList<ArrayList<View>>()
        var i = 0
        while (i < colors.size) {
            val rowViews = ArrayList<View>()
            while (i < colors.size && rowViews.size < perRow) {
                val index = i
                val v = swatch(
                    tag = "$prefix:$index",
                    color = colors[index],
                    selected = index == selectedIndex
                ) { onPick(index) }
                v.id = View.generateViewId()
                rowViews.add(v)
                i++
            }
            grid.add(rowViews)
        }

        grid.forEach { rowViews ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = false
            }
            rowViews.forEach { v ->
                row.addView(v, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                    topMargin = dp(4); bottomMargin = dp(4)
                })
            }
            repeat(perRow - rowViews.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                })
            }
            card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val activeTabId = tabViews.getOrNull(activeTab)?.id ?: View.NO_ID
        grid.forEachIndexed { r, rowViews ->
            rowViews.forEachIndexed { c, v ->
                v.nextFocusLeftId = if (c > 0) rowViews[c - 1].id else activeTabId
                v.nextFocusRightId = if (c < rowViews.size - 1) rowViews[c + 1].id else v.id
                if (r > 0 && c < grid[r - 1].size) v.nextFocusUpId = grid[r - 1][c].id
                if (r < grid.size - 1 && c < grid[r + 1].size) v.nextFocusDownId = grid[r + 1][c].id
            }
        }

        rightContent.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(6) })
    }

    private fun swatch(tag: String, color: Int, selected: Boolean, onClick: () -> Unit): View {
        val radius = dp(12).toFloat()
        fun bgDrawable(focused: Boolean): Drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            when {
                focused -> setStroke(dp(3), 0xFFFFFFFF.toInt())
                selected -> setStroke(dp(2), if (isLightColor(color)) 0xFF121218.toInt() else 0xFFFFFFFF.toInt())
                else -> setStroke(dp(1), 0x33FFFFFF)
            }
        }

        return TextView(this).apply {
            this.tag = tag
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            text = if (selected) "✓" else ""
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isLightColor(color)) 0xFF121218.toInt() else 0xFFFFFFFF.toInt())
            background = bgDrawable(false)
            contentDescription = if (selected) "Selected color" else "Color swatch"
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = bgDrawable(hasFocus)
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(110).start()
                if (hasFocus && !isRenderingPane) v.post { scrollIntoView(v) }
            }
        }
    }

    // -------------------------------------------------------- focus wiring

    private fun wireFocus() {
        val activeTabView = tabViews.getOrNull(activeTab)
        val activeTabId = activeTabView?.id ?: View.NO_ID

        // Collect top-level focusable blocks in rightContent
        val blocks = ArrayList<View>()
        for (i in 0 until rightContent.childCount) {
            val child = rightContent.getChildAt(i)
            if (childFocusable(child)) blocks.add(child)
        }

        val firstRight = blocks.firstOrNull()?.let { firstFocusableIn(it) }
        if (firstRight != null && firstRight.id == View.NO_ID) {
            firstRight.id = View.generateViewId()
        }
        tabViews.forEach { tab ->
            tab.nextFocusRightId = firstRight?.id ?: tab.id
        }

        blocks.forEach { block ->
            if (block.isFocusable) {
                if (block.id == View.NO_ID) block.id = View.generateViewId()
                block.nextFocusLeftId = activeTabId
                block.nextFocusRightId = block.id
            }
        }

        for (i in 0 until blocks.size - 1) {
            val cur = lastFocusableRowIn(blocks[i]) ?: firstFocusableIn(blocks[i]) ?: continue
            val next = firstFocusableIn(blocks[i + 1]) ?: continue
            if (cur.id == View.NO_ID) cur.id = View.generateViewId()
            if (next.id == View.NO_ID) next.id = View.generateViewId()

            // Wire all items in the last row of blocks[i] down to next
            allFocusablesInLastRow(blocks[i]).forEach { v ->
                if (v.id == View.NO_ID) v.id = View.generateViewId()
                if (v.nextFocusDownId == View.NO_ID) v.nextFocusDownId = next.id
            }
            // Wire all items in the first row of blocks[i + 1] up to cur
            allFocusablesInFirstRow(blocks[i + 1]).forEach { v ->
                if (v.id == View.NO_ID) v.id = View.generateViewId()
                if (v.nextFocusUpId == View.NO_ID) v.nextFocusUpId = cur.id
            }
        }

        // Clamp top and bottom of rightContent so UP/DOWN never escapes into sidebar
        blocks.firstOrNull()?.let { firstBlock ->
            allFocusablesInFirstRow(firstBlock).forEach { v ->
                if (v.id == View.NO_ID) v.id = View.generateViewId()
                v.nextFocusUpId = v.id
            }
        }
        blocks.lastOrNull()?.let { lastBlock ->
            allFocusablesInLastRow(lastBlock).forEach { v ->
                if (v.id == View.NO_ID) v.id = View.generateViewId()
                v.nextFocusDownId = v.id
            }
        }
    }

    private fun allFocusablesInFirstRow(v: View): List<View> {
        if (v.isFocusable) return listOf(v)
        if (v is ViewGroup && v.childCount > 0) {
            val firstRow = v.getChildAt(0)
            if (firstRow is ViewGroup) {
                return (0 until firstRow.childCount)
                    .map { firstRow.getChildAt(it) }
                    .filter { it.isFocusable }
            }
        }
        return listOfNotNull(firstFocusableIn(v))
    }

    private fun allFocusablesInLastRow(v: View): List<View> {
        if (v.isFocusable) return listOf(v)
        if (v is ViewGroup && v.childCount > 0) {
            val lastRow = v.getChildAt(v.childCount - 1)
            if (lastRow is ViewGroup) {
                return (0 until lastRow.childCount)
                    .map { lastRow.getChildAt(it) }
                    .filter { it.isFocusable }
            }
        }
        return listOfNotNull(firstFocusableIn(v))
    }

    private fun lastFocusableRowIn(v: View): View? =
        allFocusablesInLastRow(v).firstOrNull()

    // -------------------------------------------------------- confirmations

    private fun confirmResetDock() {
        confirm(
            title = "Reset pinned apps?",
            message = "All ${prefs.pins().size} pinned apps will be unpinned from the top row and returned to the grid.",
            confirmLabel = "Yes, reset pins"
        ) {
            prefs.clearPins()
            Toast.makeText(this, "Pinned apps reset", Toast.LENGTH_SHORT).show()
            renderRightPane(restoreTag = null, focusRightPane = true)
        }
    }

    private fun confirmUnhideAll(count: Int) {
        confirm(
            title = "Unhide all apps?",
            message = "$count hidden app${if (count == 1) "" else "s"} will return to the home grid.",
            confirmLabel = "Yes, unhide all"
        ) {
            prefs.clearHidden()
            renderRightPane(restoreTag = null, focusRightPane = true)
        }
    }

    private fun confirm(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        overlay?.dismiss()
        overlay = AppMenuOverlay(
            this, rootFrame,
            title = title,
            subtitle = message,
            actions = listOf(confirmLabel to onConfirm, "Cancel" to { })
        ).also { it.show() }
    }

    // ------------------------------------------------------------ wallpaper

    private val photoPermission: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * Wallpaper picking uses the launcher's own remote-friendly picker (ImagePickerOverlay):
     * Android TV's built-in pickers are a "not supported" stub or, like the photo picker,
     * can't be driven with a D-pad. Built-in wallpapers need no permission; the TV's own
     * photos need photo access, asked for here — refused just means built-ins only.
     */
    private fun pickWallpaper() {
        if (checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED) {
            openImagePicker()
        } else {
            requestPermissions(arrayOf(photoPermission), REQ_PHOTOS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PHOTOS) return
        openImagePicker()
    }

    private fun openImagePicker() {
        imagePicker?.close()
        var picked = false
        imagePicker = ImagePickerOverlay(
            activity = this,
            root = rootFrame,
            accent = prefs.accent,
            startBucket = prefs.wallpaperBucket,
            currentUri = prefs.bgUri,
            hasPhotoAccess = checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED,
            onPick = { uri, bucket ->
                picked = true
                prefs.wallpaperBucket = bucket
                applyPickedWallpaper(uri)
            },
            onClosed = {
                imagePicker = null
                if (!picked) onWallpaperPickCancelled()
            }
        ).also { it.show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_HOME) {
            val isNowDefault = DefaultLauncherHelper.isDefaultHome(this)
            if (isNowDefault) {
                prefs.defaultLauncherEnabled = true
                prefs.launchOnBoot = true
                Toast.makeText(
                    this,
                    "Simple TV Launcher is now your default launcher",
                    Toast.LENGTH_SHORT
                ).show()
                renderRightPane(restoreTag = null, focusRightPane = true)
            } else {
                val elapsed = SystemClock.elapsedRealtime() - roleRequestStartMs
                if (elapsed in 0L..450L) {
                    // System role dialog was suppressed on this firmware; open Home selector fallback
                    DefaultLauncherHelper.openHomeSelectorFallback(this)
                } else {
                    renderRightPane(restoreTag = "home:make_default", focusRightPane = true)
                }
            }
            return
        }
    }

    private fun applyPickedWallpaper(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        prefs.bgUri = uri.toString()
        prefs.bgMode = 2
        renderRightPane(restoreTag = "bg_action:pick", focusRightPane = true)
        val appCtx = applicationContext
        Thread {
            WallpaperStore.convertAndSave(appCtx, prefs, uri)
        }.start()
    }

    private fun onWallpaperPickCancelled() {
        // No photo chosen: only keep photo mode if one is already active.
        if (prefs.bgMode != 2) resetToSolidBlack()
        renderRightPane(
            restoreTag = if (prefs.bgMode == 2) "bg_action:pick" else "bg_mode:1",
            focusRightPane = true
        )
    }

    /** Clears any wallpaper and falls back to the default solid black background. */
    private fun resetToSolidBlack() {
        prefs.bgUri = null
        prefs.bgCachedUri = null
        WallpaperStore.clear()
        prefs.bgMode = 1
        prefs.bgIndex = 0
    }

    // -------------------------------------------------------------- helpers

    private fun scrollIntoView(v: View) {
        if (paletteScrim != null) return
        if (v.height == 0) return
        if (v === firstFocusableIn(rightContent)) {
            if (rightScroller.scrollY > 0) rightScroller.smoothScrollTo(0, 0)
            return
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val scrollerLoc = IntArray(2)
        rightScroller.getLocationOnScreen(scrollerLoc)
        val viewTop = loc[1] - scrollerLoc[1] + rightScroller.scrollY
        val viewBottom = viewTop + v.height
        val visibleTop = rightScroller.scrollY + dp(32)
        val visibleBottom = rightScroller.scrollY + rightScroller.height - dp(24)
        when {
            viewTop < visibleTop ->
                rightScroller.smoothScrollTo(0, (viewTop - dp(64)).coerceAtLeast(0))
            viewBottom > visibleBottom ->
                rightScroller.smoothScrollTo(0, (viewBottom - rightScroller.height + dp(48)).coerceAtLeast(0))
        }
    }

    private fun findFocusedTag(root: View): String? {
        if (root.isFocused && root.tag is String) return root.tag as String
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFocusedTag(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findViewByTagRecursive(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByTagRecursive(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun childFocusable(v: View): Boolean =
        v.isFocusable || (v is ViewGroup && firstFocusableIn(v) != null)

    private fun firstFocusableIn(v: View): View? {
        if (v.isFocusable) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstFocusableIn(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun hasFocusedChild(v: View): Boolean {
        if (v.isFocused) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasFocusedChild(v.getChildAt(i))) return true
        }
        return false
    }

    private fun pruneStale(keys: Set<String>): Set<String> {
        val alive = keys.filter { key ->
            try {
                packageManager.getApplicationInfo(key.substringBefore("/"), 0) != null
            } catch (_: Exception) { false }
        }.toSet()
        if (alive != keys) (keys - alive).forEach { prefs.unhide(it) }
        return alive
    }

    private fun labelFor(key: String): String = try {
        val ai = packageManager.getApplicationInfo(key.substringBefore("/"), 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) { key }

    private fun isLightColor(c: Int): Boolean {
        val l = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
        return l > 165
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        fun ch(shift: Int) =
            (((a ushr shift) and 0xFF) * inv + ((b ushr shift) and 0xFF) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REQ_PHOTOS = 43
        private const val REQ_DEFAULT_HOME = 42
        private val BG_ROOT = Color.parseColor("#FF0B0B10")
        private val CARD_BG = Color.parseColor("#FF171722")
        private val TEXT_PRIMARY = Color.parseColor("#FFF5F5F7")
        private val TEXT_SECONDARY = Color.parseColor("#FF9898A6")
        private val TEXT_MUTED = Color.parseColor("#FF6E6E7A")
        private val COLOR_DANGER = Color.parseColor("#FFFF453A")
    }
}
