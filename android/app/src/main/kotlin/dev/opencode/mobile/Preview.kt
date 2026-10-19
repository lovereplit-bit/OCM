package dev.opencode.mobile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Preview windows: pages the agent serves on the phone (a dev server on localhost) shown in a window that floats over the
 * app or fills the screen, with tabs, like a browser tab beside the chat on a computer.
 */
class PreviewManager(private val activity: MainActivity, private val layer: FrameLayout, private val palette: Palette) {
    private var window: PreviewWindow? = null
    private var pill: TextView? = null

    fun show(url: String) {
        val current = window ?: PreviewWindow(activity, layer, palette, this).also {
            window = it
            layer.addView(it)
            it.placeInitially()
        }

        current.open(url)
        current.setMode(if (current.mode == PreviewWindow.Mode.MINIMIZED) PreviewWindow.Mode.FLOAT else current.mode)
        refreshPill()
    }

    val isOpen: Boolean get() = window != null

    fun restore() {
        window?.setMode(PreviewWindow.Mode.FLOAT)
        refreshPill()
    }

    /** Returns true when the back press was used up by a preview. */
    fun onBack(): Boolean {
        val w = window ?: return false

        if (w.mode != PreviewWindow.Mode.FULL) return false

        if (!w.goBack()) w.setMode(PreviewWindow.Mode.FLOAT)

        refreshPill()

        return true
    }

    fun closeAll() {
        window?.destroy()
        window?.let { layer.removeView(it) }
        window = null
        refreshPill()
    }

    fun windowClosed() {
        window?.let { layer.removeView(it) }
        window = null
        refreshPill()
    }

    fun refreshPill() {
        val w = window

        if (w == null || w.mode != PreviewWindow.Mode.MINIMIZED) {
            pill?.let { layer.removeView(it) }
            pill = null

            return
        }

        val label = w.currentHost() + if (w.tabCount() > 1) "  +${w.tabCount() - 1}" else ""
        val existing = pill

        if (existing != null) {
            existing.text = label

            return
        }

        val chip = Ui.text(activity, label, 13f, palette.text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(activity, 14), Ui.dp(activity, 10), Ui.dp(activity, 14), Ui.dp(activity, 10))
            background = Ui.rounded(activity, palette.layer, 20f, palette.border)
            elevation = Ui.dp(activity, 6).toFloat()
            setOnClickListener { restore() }
        }

        pill = chip
        layer.addView(chip, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(activity, 12), Ui.dp(activity, 76))
        })
    }

    fun destroy() = closeAll()
}

class PreviewWindow(
    private val activity: MainActivity,
    private val layer: FrameLayout,
    private val palette: Palette,
    private val manager: PreviewManager,
) : FrameLayout(activity) {
    enum class Mode { FLOAT, FULL, MINIMIZED }

    private class Tab(val web: WebView, var url: String, var title: String = "") {
        var retries = 0
    }

    var mode = Mode.FLOAT
        private set

    private val tabs = ArrayList<Tab>()
    private var selected = -1
    private val tabRow = Ui.horizontal(activity)
    private val tabScroll = HorizontalScrollView(activity)
    private val hostLabel = Ui.text(activity, "", 12f, palette.muted, mono = true)
    private val content = FrameLayout(activity)
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val fullButton: android.widget.ImageView
    private val handle = View(activity)
    private val main = Handler(Looper.getMainLooper())

    // Where the floating window sits, so it comes back to the same place after full screen.
    private var floatX = 0f
    private var floatY = 0f
    private var floatW = 0
    private var floatH = 0

    init {
        elevation = Ui.dp(activity, 12).toFloat()
        background = Ui.rounded(activity, palette.bg, 14f, palette.border)
        clipToOutline = true

        val column = Ui.vertical(activity)
        val bar = Ui.vertical(activity).apply { setBackgroundColor(palette.layer) }

        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabRow)
        bar.addView(tabScroll, Ui.params(Ui.MATCH, Ui.dp(activity, 36)))

        val controls = Ui.horizontal(activity).apply { setPadding(Ui.dp(activity, 4), 0, Ui.dp(activity, 4), 0) }

        controls.addView(Ui.iconButton(activity, R.drawable.ic_arrow_left, tr("Назад", "Back"), palette, sizeDp = 36) { goBack() })
        hostLabel.maxLines = 1
        hostLabel.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        controls.addView(hostLabel, Ui.params(0, Ui.WRAP, weight = 1f, left = Ui.dp(activity, 4), right = Ui.dp(activity, 4)))
        controls.addView(Ui.iconButton(activity, R.drawable.ic_reload, tr("Обновить", "Reload"), palette, sizeDp = 36) { currentTab()?.let { it.retries = 0; it.web.loadUrl(it.url) } })
        controls.addView(Ui.iconButton(activity, R.drawable.ic_open_external, tr("Открыть в браузере", "Open in browser"), palette, sizeDp = 36) { currentTab()?.let { activity.openExternal(it.web.url ?: it.url) } })
        controls.addView(Ui.iconButton(activity, R.drawable.ic_minimize, tr("Свернуть", "Minimize"), palette, sizeDp = 36) { setMode(Mode.MINIMIZED); manager.refreshPill() })
        fullButton = Ui.iconButton(activity, R.drawable.ic_fullscreen, tr("На весь экран", "Full screen"), palette, sizeDp = 36) {
            setMode(if (mode == Mode.FULL) Mode.FLOAT else Mode.FULL)
        }
        controls.addView(fullButton)
        controls.addView(Ui.iconButton(activity, R.drawable.ic_close, tr("Закрыть окно", "Close window"), palette, sizeDp = 36) { destroy(); manager.windowClosed() })
        bar.addView(controls, Ui.params(Ui.MATCH, Ui.dp(activity, 40)))
        installDrag(bar)

        progress.max = 100
        progress.isIndeterminate = false
        progress.progressTintList = android.content.res.ColorStateList.valueOf(palette.text)
        progress.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.layer)

        column.addView(bar, Ui.params(Ui.MATCH, Ui.WRAP))
        column.addView(progress, Ui.params(Ui.MATCH, Ui.dp(activity, 2)))
        column.addView(content, Ui.params(Ui.MATCH, 0, weight = 1f))
        addView(column, LayoutParams(Ui.MATCH, Ui.MATCH))

        handle.background = Ui.rounded(activity, palette.layerStrong, 6f)
        addView(handle, LayoutParams(Ui.dp(activity, 28), Ui.dp(activity, 28), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(activity, 4), Ui.dp(activity, 4))
        })
        installResize()
    }

    fun placeInitially() {
        layer.post {
            val w = layer.width
            val h = layer.height

            floatW = minOf(w - Ui.dp(activity, 20), Ui.dp(activity, 460))
            floatH = (h * 0.5f).toInt().coerceAtLeast(Ui.dp(activity, 260))
            floatX = ((w - floatW) / 2f)
            floatY = (h - floatH - Ui.dp(activity, 84)).toFloat().coerceAtLeast(Ui.dp(activity, 8).toFloat())
            applyMode()
        }
    }

    // ---- tabs --------------------------------------------------------------------------------------------------------

    fun open(url: String) {
        val key = Urls.parse(url)?.let { Urls.displayHost(url) }
        val existing = tabs.indexOfFirst { Urls.displayHost(it.url) == key }

        if (existing >= 0) {
            select(existing)
            tabs[existing].let { it.url = url; it.retries = 0; it.web.loadUrl(url) }

            return
        }

        val tab = Tab(createWebView(), url)

        tabs += tab
        content.addView(tab.web, LayoutParams(Ui.MATCH, Ui.MATCH))
        select(tabs.size - 1)
        tab.web.loadUrl(url)
    }

    private fun select(index: Int) {
        selected = index

        tabs.forEachIndexed { i, tab -> tab.web.visibility = if (i == index) View.VISIBLE else View.GONE }
        rebuildTabs()
        hostLabel.text = tabs.getOrNull(index)?.let { it.web.url ?: it.url }.orEmpty()
        progress.progress = 0
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return

        content.removeView(tab.web)
        tab.web.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            manager.windowClosed()

            return
        }

        select(minOf(index, tabs.size - 1))
        manager.refreshPill()
    }

    private fun rebuildTabs() {
        tabRow.removeAllViews()

        tabs.forEachIndexed { i, tab ->
            val active = i == selected
            val chip = Ui.horizontal(activity).apply {
                background = Ui.rounded(activity, if (active) palette.bg else Color.TRANSPARENT, 8f, if (active) palette.border else null)
                setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 2), 0)
                setOnClickListener { select(i) }
            }
            val label = Ui.text(activity, tab.title.ifEmpty { Urls.displayHost(tab.url) }, 12f, if (active) palette.text else palette.muted, bold = active)

            label.maxLines = 1
            label.maxWidth = Ui.dp(activity, 150)
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            chip.addView(label)
            chip.addView(Ui.iconButton(activity, R.drawable.ic_close, tr("Закрыть вкладку", "Close tab"), palette, sizeDp = 28) { closeTab(i) })
            tabRow.addView(chip, Ui.params(Ui.WRAP, Ui.dp(activity, 30), left = Ui.dp(activity, 4), top = Ui.dp(activity, 3)))
        }
    }

    fun tabCount() = tabs.size

    fun currentHost(): String = currentTab()?.let { Urls.displayHost(it.url) }.orEmpty()

    private fun currentTab() = tabs.getOrNull(selected)

    fun goBack(): Boolean {
        val tab = currentTab() ?: return false

        if (!tab.web.canGoBack()) return false

        tab.web.goBack()

        return true
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)

        for (tab in tabs) {
            content.removeView(tab.web)
            tab.web.destroy()
        }

        tabs.clear()
    }

    // ---- modes, dragging, resizing -----------------------------------------------------------------------------------

    fun setMode(next: Mode) {
        if (mode == Mode.FLOAT && next != Mode.FLOAT) {
            floatX = x
            floatY = y
            floatW = width.takeIf { it > 0 } ?: floatW
            floatH = height.takeIf { it > 0 } ?: floatH
        }

        mode = next
        applyMode()
    }

    private fun applyMode() {
        when (mode) {
            Mode.FULL -> {
                layoutParams = LayoutParams(Ui.MATCH, Ui.MATCH)
                translationX = 0f
                translationY = 0f
                x = 0f
                y = 0f
                visibility = View.VISIBLE
                handle.visibility = View.GONE
                fullButton.setImageResource(R.drawable.ic_fullscreen_exit)
            }
            Mode.FLOAT -> {
                layoutParams = LayoutParams(maxOf(floatW, Ui.dp(activity, 220)), maxOf(floatH, Ui.dp(activity, 200)))
                x = floatX
                y = floatY
                visibility = View.VISIBLE
                handle.visibility = View.VISIBLE
                fullButton.setImageResource(R.drawable.ic_fullscreen)
            }
            Mode.MINIMIZED -> visibility = View.GONE
        }

        requestLayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(bar: View) {
        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f

        bar.setOnTouchListener { _, event ->
            if (mode != Mode.FLOAT) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originX = x
                    originY = y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    x = (originX + event.rawX - startX).coerceIn(-width + Ui.dp(activity, 80).toFloat(), (layer.width - Ui.dp(activity, 80)).toFloat())
                    y = (originY + event.rawY - startY).coerceIn(0f, (layer.height - Ui.dp(activity, 48)).toFloat())
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installResize() {
        var startX = 0f
        var startY = 0f
        var startW = 0
        var startH = 0

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startW = width
                    startH = height
                    parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = layoutParams

                    params.width = (startW + event.rawX - startX).toInt().coerceIn(Ui.dp(activity, 220), maxOf(Ui.dp(activity, 220), layer.width - x.toInt()))
                    params.height = (startH + event.rawY - startY).toInt().coerceIn(Ui.dp(activity, 200), maxOf(Ui.dp(activity, 200), layer.height - y.toInt()))
                    layoutParams = params
                    true
                }
                else -> true
            }
        }
    }

    // ---- web views ---------------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(activity)

        WebConfig.apply(web.settings, "OpenCodePreview/${BuildConfig.VERSION_NAME}")
        // Links that open a new window load in the same tab; there is no JavaScript bridge in a preview.
        web.settings.setSupportMultipleWindows(false)
        web.setBackgroundColor(Color.WHITE)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            Downloads.fromUrl(activity, url, userAgent, disposition, mime) { result ->
                activity.runOnUiThread {
                    Ui.toast(activity, result.fold({ tr("Сохранено: ", "Saved: ") + it }, { tr("Не удалось скачать: ", "Download failed: ") + (it.message ?: "") }))
                }
            }
        }

        web.webChromeClient = object : AppChromeClient(activity) {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabs.getOrNull(selected)?.web === view) progress.progress = if (newProgress >= 100) 0 else newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabs.firstOrNull { it.web === view }?.let {
                    it.title = title.orEmpty()
                    rebuildTabs()
                }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) = request.deny()
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                val scheme = request.url.scheme

                if (scheme == "http" || scheme == "https") {
                    // The app's own server is not a page to preview.
                    return activity.isReserved(target)
                }

                if (scheme == "mailto" || scheme == "tel") activity.openExternal(target)

                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tabs.firstOrNull { it.web === view }?.let {
                    if (!url.startsWith("data:")) it.url = url
                    if (tabs.getOrNull(selected) === it) hostLabel.text = url
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tabs.firstOrNull { it.web === view }?.retries = 0
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return

                val tab = tabs.firstOrNull { it.web === view } ?: return

                // A dev server the agent just started may not be listening yet; try again for a few seconds first.
                if (tab.retries < 8 && error.errorCode == WebViewClient.ERROR_CONNECT) {
                    tab.retries++
                    main.postDelayed({ if (tabs.contains(tab)) tab.web.loadUrl(tab.url) }, 1200)

                    return
                }

                val message = error.description?.toString().orEmpty().replace("<", "&lt;")
                val page = "<html><body style=\"font-family:sans-serif;padding:24px;color:#888;background:#161616\">" +
                    "<h3 style=\"color:#f2f2f2\">${tr("Страница недоступна", "This page is unavailable")}</h3>" +
                    "<p>${Urls.displayHost(tab.url)}</p><p>$message</p></body></html>"

                view.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
            }
        }

        return web
    }
}
