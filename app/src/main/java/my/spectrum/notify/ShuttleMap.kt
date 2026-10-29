package my.spectrum.notify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * The OpenStreetMap map on the Shuttle screen (osmdroid): every place with coordinates as a dot, and either one
 * route's line or a planned journey (bus legs in the route colour, walks dashed). Tap a dot to pick it.
 */
class ShuttleMap(private val ctx: Context, private val onPlace: (ShuttleModel.Place) -> Unit) {

    val view: MapView

    /** What is drawn now, so a redraw with the same content keeps where you panned and zoomed. */
    private var shownKey = ""

    init {
        // osmdroid keeps its settings and tile cache in the app's own folders; the user agent is required by OSM
        val conf = Configuration.getInstance()
        conf.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = ctx.packageName
        conf.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        conf.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        view = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            minZoomLevel = 13.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            // dark theme: the map in dimmed, inverted greys (plain inversion turns parks purple)
            if (Ui.dark) overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(floatArrayOf(
                -0.25f, -0.5f, -0.1f, 0f, 220f,
                -0.25f, -0.5f, -0.1f, 0f, 222f,
                -0.25f, -0.5f, -0.1f, 0f, 228f,
                0f, 0f, 0f, 1f, 0f,
            )))
            controller.setZoom(15.5)
            controller.setCenter(GeoPoint(3.1205, 101.6555))
            contentDescription = "Map of the UM shuttle and Rapid KL stops"
        }
        keepTouches(view)
    }

    /** Inside a scroll view: while a finger is on the map, the map pans and the page does not scroll. */
    @SuppressLint("ClickableViewAccessibility")
    private fun keepTouches(v: MapView) {
        v.setOnTouchListener { m, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> m.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> m.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun pt(p: ShuttleModel.Place): GeoPoint? = if (p.lat != null && p.lng != null) GeoPoint(p.lat, p.lng) else null

    private fun dotIcon(fill: Int, ring: Int, sizeDp: Int): BitmapDrawable {
        val px = ctx.dp(sizeDp)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        c.drawCircle(px / 2f, px / 2f, px / 2f, paint)
        paint.color = fill
        c.drawCircle(px / 2f, px / 2f, px / 2f - ctx.dp(2), paint)
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun line(points: List<GeoPoint>, color: Int, widthDp: Int, dashed: Boolean): Polyline = Polyline(view).apply {
        setPoints(points)
        outlinePaint.color = color
        outlinePaint.strokeWidth = ctx.dp(widthDp).toFloat()
        outlinePaint.strokeCap = Paint.Cap.ROUND
        if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(ctx.dp(6).toFloat(), ctx.dp(6).toFloat()), 0f)
        infoWindow = null
    }

    /** The stops a ride passes, in order (a frequent loop's ride can run past the first stop into the next lap). */
    private fun rideStops(r: ShuttleModel.Ride): List<String> {
        val s = r.route.stops
        return if (r.toIdx > r.fromIdx) s.subList(r.fromIdx, r.toIdx + 1) else s.subList(r.fromIdx, s.size) + s.subList(1, r.toIdx + 1)
    }

    /**
     * Draw [route] (its whole line), or [journey] when one is chosen. [from]/[to] get bigger dots.
     * The camera moves to fit only when what is drawn changed.
     */
    fun show(d: ShuttleModel.Data, route: ShuttleModel.Route?, journey: ShuttleModel.Journey?,
             from: ShuttleModel.Place?, to: ShuttleModel.Place?) {
        val key = (journey?.key ?: "r:${route?.id}") + "|${from?.id}|${to?.id}"
        val moved = key != shownKey
        shownKey = key
        view.overlays.clear()
        val fit = ArrayList<GeoPoint>()

        if (journey != null) {
            for (leg in journey.legs) when (leg) {
                is ShuttleModel.Leg.Bus -> {
                    val pts = rideStops(leg.ride).mapNotNull { d.places[it]?.let(::pt) }
                    if (pts.size >= 2) view.overlays.add(line(pts, leg.ride.route.color, 5, false))
                    fit.addAll(pts)
                }
                is ShuttleModel.Leg.Walk -> {
                    val a = pt(leg.from); val b = pt(leg.to)
                    if (a != null && b != null) { view.overlays.add(line(listOf(a, b), Ui.DIM, 3, true)); fit.add(a); fit.add(b) }
                }
            }
        } else if (route != null) {
            val pts = route.stops.mapNotNull { d.places[it]?.let(::pt) }
            if (pts.size >= 2) view.overlays.add(line(pts, route.color, 4, false))
            fit.addAll(pts)
        }

        // every place with coordinates: stops as dots, buildings as squares-ish (smaller, outlined)
        val onRoute = HashSet<String>()
        journey?.buses?.forEach { onRoute.addAll(rideStops(it.ride)) }
        if (journey == null) route?.stops?.let { onRoute.addAll(it) }
        for (p in d.places.values) {
            val g = pt(p) ?: continue
            val chosen = p.id == from?.id || p.id == to?.id
            val icon = when {
                chosen -> dotIcon(if (p.id == from?.id) Ui.GREEN else Ui.RED, 0xFFFFFFFF.toInt(), 18)
                !p.isStop -> dotIcon(0xFFFFFFFF.toInt(), Ui.ORANGE, 12)
                p.id in onRoute -> dotIcon(0xFFFFFFFF.toInt(), Ui.CYAN, 12)
                else -> dotIcon(0xFFFFFFFF.toInt(), 0xFF8A8F98.toInt(), 10)
            }
            view.overlays.add(Marker(view).apply {
                position = g
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setIcon(icon)
                title = p.name
                infoWindow = null
                setOnMarkerClickListener { _, _ -> onPlace(p); true }
            })
            if (chosen) fit.add(g)
        }
        view.overlays.add(CopyrightOverlay(ctx).apply { setTextColor(Ui.DIM) })
        view.invalidate()

        if (moved && fit.isNotEmpty()) {
            view.post {
                if (fit.size == 1) { view.controller.setZoom(17.0); view.controller.setCenter(fit[0]) }
                else view.zoomToBoundingBox(BoundingBox.fromGeoPoints(fit).increaseByScale(1.3f), false, ctx.dp(24))
            }
        }
    }

    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
}
