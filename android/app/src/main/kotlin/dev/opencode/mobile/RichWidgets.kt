package dev.opencode.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RemoteViews

/**
 * Draws rich widget content ([WidgetSpec.isRich]) for the agent: HTML, CSS and JavaScript through a hidden [WebView]
 * that is screenshotted into home-screen widget bitmaps, SVG files by wrapping them into such a page, and raster
 * pictures (PNG, JPEG, WebP, GIF) through the system decoder.
 *
 * Rendering runs on the UI thread, because a WebView may only be created and driven from the main Looper (a background
 * HandlerThread makes the Chromium-backed WebView throw). All work is posted or callback-driven, so nothing blocks the
 * UI, and one named widget keeps at most one hidden page for reuse on later taps.
 *
 * A rich page may define `window.widgetTap()`: tapping such a widget on the home screen calls it and keeps
 * screenshotting frames while the page holds `window.__ocBusy = true` (at most [MAX_FRAMES]), which is how the page
 * animates its own press feedback and other one-tap actions. Pages need no network: inline CSS and JS, and images
 * as `data:` URLs.
 */
object RichWidgets {
    /** How many frames one tap animation is allowed to play. */
    const val MAX_FRAMES = 24

    /** Delay between animation frames, in ms. */
    const val FRAME_MS = 60L

    /** Pause after a page reports "finished loading" before the first static screenshot, in ms. */
    private const val SETTLE_MS = 140L

    /** Hard cap for tap handling so a page can never keep the broadcast receiver busy forever. */
    private const val TAP_TIMEOUT_MS = MAX_FRAMES * FRAME_MS + 1_500L

    /** The fake origin a widget page loads from: self-contained, so nothing may depend on real network paths. */
    private const val BASE_URI = "https://widget.opencode.local/"

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** One WebView per widget name; only ever touched on the UI thread. */
    private val pages = HashMap<String, WebView>()

    /** The spec hash each stored page was built from; empty until the page finishes loading. */
    private val pageHash = HashMap<String, String>()

    /** Last drawn bitmap of every rich widget, key "name/hash", so the widget repaints without re-rendering. */
    private val bitmaps = HashMap<String, Bitmap>()

    // ---------------------------------------------------------------- public entry points (thread safe)

    /** The most recent bitmap for [spec], or null while it has not been rendered since the spec changed. */
    fun cached(spec: WidgetSpec): Bitmap? = synchronized(bitmaps) { bitmaps[spec.name + "/" + spec.hash()] }

    /** Drops every cache entry and hidden page of one widget. */
    fun forget(name: String) {
        synchronized(bitmaps) { bitmaps.keys.removeAll { it.startsWith("$name/") } }

        handler.post { dropPage(name) }
    }

    /** Schedules a build so the widget gets its first bitmap; the result publishes itself. */
    fun load(context: Context, spec: WidgetSpec) {
        handler.post { safe { ensurePage(context.applicationContext, spec) } }
    }

    /** A tap PendingIntent that plays this widget's tap animation. */
    fun tapIntent(context: Context, name: String): PendingIntent {
        val id = WidgetStore(context).assignments().filterValues { it == name }.keys.firstOrNull() ?: -1

        val intent = Intent(context, AgentWidgetProvider::class.java)
            .setAction(Widgets.ACTION_TAP)
            .putExtra(Widgets.EXTRA_ID, id)

        return PendingIntent.getBroadcast(context, name.hashCode() and 0x7FFFFFFF, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Plays the page's own tap animation, then calls [done] exactly once. */
    fun tap(context: Context, spec: WidgetSpec, done: () -> Unit) {
        val app = context.applicationContext
        val finish = finishOnce(done)

        handler.post { safe { tapAt(app, spec, finish) } ?: finish() }
        handler.postDelayed(finish, TAP_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- UI thread

    private fun dropPage(name: String) {
        safe { pages.remove(name)?.destroy() }
        pageHash.remove(name)
    }

    private fun ensurePage(app: Context, spec: WidgetSpec) {
        val hash = spec.hash()

        if (pageHash[spec.name] == hash) return

        safe { pages.remove(spec.name)?.destroy() }
        pages.remove(spec.name)
        pageHash.remove(spec.name)

        if (spec.richKind == WidgetContent.KIND_IMAGE) {
            paintRaster(app, spec)

            pageHash[spec.name] = hash
        } else {
            val view = safe { buildPage(app, spec, hash) } ?: return

            pages[spec.name] = view
        }
    }

    /** Pictures decode straight to a bitmap; there is no page to keep around. */
    private fun paintRaster(app: Context, spec: WidgetSpec) {
        val source = decode(WidgetContent.bytesOf(spec.src)) ?: return

        val w = sizeOf(spec.w, WidgetContent.DEFAULT_W)
        val h = sizeOf(spec.h, WidgetContent.DEFAULT_H)
        val bitmap = rounded(fitCenter(source, w, h))

        store(spec, bitmap)
        publish(app, spec, bitmap)
    }

    /** Shows the freshly rendered bitmap for every placed instance of the widget [spec.name]. */
    private fun pageReady(app: Context, spec: WidgetSpec, view: WebView, hash: String) {
        // A newer page may have replaced this one while it was loading or settling.
        if (pages[spec.name] !== view || pageHash[spec.name] != hash || spec.hash() != hash) return

        if (view.width <= 0) return

        val bitmap = safe { capture(view) }

        if (bitmap != null) {
            store(spec, bitmap)
            publish(app, spec, bitmap)
        }
    }

    private fun buildPage(app: Context, spec: WidgetSpec, hash: String): WebView {
        val raw = WidgetContent.bytesOf(spec.src)
        val view = WebView(app)

        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.loadWithOverviewMode = false
        view.settings.useWideViewPort = false
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false

        val w = sizeOf(spec.w, WidgetContent.DEFAULT_W)
        val h = sizeOf(spec.h, WidgetContent.DEFAULT_H)
        val html = when (spec.richKind) {
            WidgetContent.KIND_SVG -> svgPage(raw)
            else -> String(raw, Charsets.UTF_8)
        }

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(finished: WebView, url: String?) {
                // Ignore a late callback from a page that has already been replaced.
                if (pages[spec.name] !== finished) return

                pageHash[spec.name] = hash

                handler.postDelayed({ pageReady(app, spec, finished, hash) }, SETTLE_MS)
            }
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        view.loadDataWithBaseURL(BASE_URI, html, "text/html", "utf-8", null)

        return view
    }

    private fun sizeOf(want: Int, fallback: Int) =
        if (want <= 0) fallback else want.coerceIn(1, WidgetContent.MAX_PX)

    // ---------------------------------------------------------------- tap animation

    private fun tapAt(app: Context, spec: WidgetSpec, finish: () -> Unit) {
        if (pageHash[spec.name] != spec.hash()) {
            // Not painted (or replaced since loading): rebuild now, the animation plays on the next tap.
            ensurePage(app, spec)

            finish()
            return
        }

        val view = pages[spec.name] ?: run { finish(); return }
        val started = System.currentTimeMillis()

        safe {
            view.evaluateJavascript(
                "(function(){ if (typeof widgetTap === 'function') widgetTap(); " +
                    "return String(window.__ocBusy === true); })()",
            ) { busy ->
                if (busy.contains("true")) {
                    paintFrames(app, spec, view, MAX_FRAMES, started, finish)
                } else {
                    // The handler mutated state without animating; one repaint keeps the widget in sync.
                    val bitmap = safe { capture(view) }

                    if (bitmap != null) {
                        store(spec, bitmap)
                        publish(app, spec, bitmap)
                    }

                    finish()
                }
            }
        } ?: finish()
    }

    private fun paintFrames(app: Context, spec: WidgetSpec, view: WebView, left: Int, started: Long, finish: () -> Unit) {
        val bitmap = safe { capture(view) }

        if (bitmap != null) {
            store(spec, bitmap)
            publish(app, spec, bitmap)
        }

        if (left <= 0 || System.currentTimeMillis() - started > TAP_TIMEOUT_MS) {
            finish()
            return
        }

        handler.postDelayed({
            safe {
                view.evaluateJavascript("String(window.__ocBusy === true)") { busy ->
                    if (busy.contains("true")) paintFrames(app, spec, view, left - 1, started, finish)
                    else finish()
                }
            } ?: finish()
        }, FRAME_MS)
    }

    private fun finishOnce(done: () -> Unit): () -> Unit {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)

        return {
            if (once.compareAndSet(false, true)) done()
        }
    }

    // ---------------------------------------------------------------- bitmap plumbing

    /** Screenshots the current frame of a page. */
    private fun capture(view: WebView): Bitmap {
        val raw = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

        view.draw(Canvas(raw))

        return rounded(raw)
    }

    /** Corners match the widget shell closely enough that the scaled picture looks at home on the grid. */
    private fun rounded(source: Bitmap): Bitmap {
        val radius = source.width * 0.085f
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRoundRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), radius, radius, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        source.recycle()

        return out
    }

    /** Scales [source] into the canvas, centred, keeping its aspect ratio, then clears the original. */
    private fun fitCenter(source: Bitmap, w: Int, h: Int): Bitmap {
        val scale = minOf(w.toFloat() / source.width, h.toFloat() / source.height)
        val dw = maxOf(1, (source.width * scale).toInt())
        val dh = maxOf(1, (source.height * scale).toInt())

        val scaled = Bitmap.createScaledBitmap(source, dw, dh, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(scaled, (w - dw) / 2f, (h - dh) / 2f, paint)
        source.recycle()

        return out
    }

    private fun store(spec: WidgetSpec, bitmap: Bitmap) {
        synchronized(bitmaps) {
            bitmaps.keys.removeAll { it.startsWith("${spec.name}/") }
            bitmaps[spec.name + "/" + spec.hash()] = bitmap
        }
    }

    private fun publish(app: Context, spec: WidgetSpec, bitmap: Bitmap) {
        val manager = AppWidgetManager.getInstance(app)
        val ids = WidgetStore(app).assignments().filterValues { it == spec.name }.keys

        for (id in ids) safe { manager.updateAppWidget(id, views(app, spec, bitmap)) }
    }

    /** RemoteViews showing one rendered bitmap; [AgentWidgetProvider.renderViews] and the animation both use it. */
    fun views(app: Context, spec: WidgetSpec, bitmap: Bitmap): RemoteViews {
        val v = RemoteViews(app.packageName, R.layout.widget_web)

        v.setImageViewBitmap(R.id.widget_image, bitmap)
        v.setOnClickPendingIntent(R.id.widget_web_root, tapIntent(app, spec.name))

        return v
    }

    /** SVG files draw through the same page pipeline, since Android has no built-in SVG decoder. */
    private fun svgPage(raw: ByteArray): String {
        val data = "data:image/svg+xml;base64," + Base64.encodeToString(raw, Base64.NO_WRAP)

        return """
            <!doctype html><html><head><meta charset="utf-8"><style>
            html,body{margin:0;padding:0;overflow:hidden;background:transparent;width:100%;height:100%}
            img{display:block;width:100%;height:100%;object-fit:contain}
            </style></head><body><img src="$data"></body></html>
        """.trimIndent()
    }

    /** Runs [block] without letting a dead page take the render thread down. */
    private fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    /** Decodes raster pictures; null when the bytes are not actually a picture the decoder accepts. */
    private fun decode(raw: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(raw, 0, raw.size)
    } catch (_: Throwable) {
        null
    }
}
