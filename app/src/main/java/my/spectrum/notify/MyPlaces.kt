package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Places you add yourself for the shuttle (a house, a shop, a gate): a name and a point, kept on this phone in the
 * shuttle settings and merged into [Shuttle.data], so every place picker, the map and the trip planner use them.
 * Added by searching the map, by sharing a place from Google Maps, or by dropping a pin.
 */
object MyPlaces {
    const val PREFIX = "my:"
    /** Middle of UM, where the pin starts and what "far from campus" is measured from. */
    private const val UM_LAT = 3.1205
    private const val UM_LNG = 101.6555
    /** A place this close to a stop uses it for the bus (class leave-by alerts); further away it is walked. */
    private const val NEAR_STOP_M = 600

    fun isMine(id: String) = id.startsWith(PREFIX)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("shuttle", Context.MODE_PRIVATE)

    private fun list(ctx: Context): JSONArray =
        try { JSONArray(sp(ctx).getString("mine", "[]")) } catch (_: Exception) { JSONArray() }

    private fun write(ctx: Context, a: JSONArray) {
        sp(ctx).edit().putString("mine", a.toString()).apply()
        Shuttle.invalidate()
    }

    /** Your places as shuttle places, each with the bus stop it is closest to (when one is close enough). */
    fun places(ctx: Context, stops: Collection<ShuttleModel.Place>): List<ShuttleModel.Place> {
        val a = list(ctx)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat"); val lng = o.optDouble("lng")
            if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
            val bare = ShuttleModel.Place(o.optString("id"), o.optString("name"), false, "", "$lat,$lng", lat, lng)
            val near = stops.filter { it.isStop }.mapNotNull { s -> ShuttleModel.meters(bare, s)?.let { s to it } }
                .minByOrNull { it.second }?.takeIf { it.second <= NEAR_STOP_M }?.first?.id ?: ""
            ShuttleModel.Place(bare.id, bare.name, false, near, bare.query, lat, lng)
        }
    }

    /** Adds a place, or changes [id]'s; returns its id. */
    fun save(ctx: Context, id: String?, name: String, lat: Double, lng: Double): String {
        val a = list(ctx)
        val pid = id ?: (PREFIX + System.currentTimeMillis())
        val o = JSONObject().put("id", pid).put("name", name.trim()).put("lat", lat).put("lng", lng)
        val at = (0 until a.length()).firstOrNull { a.optJSONObject(it)?.optString("id") == pid }
        if (at != null) a.put(at, o) else a.put(o)
        write(ctx, a)
        return pid
    }

    fun delete(ctx: Context, id: String) {
        val a = list(ctx)
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { keep.put(it) }
        write(ctx, keep)
    }

    fun metersFromUm(lat: Double, lng: Double): Int = ShuttleModel.meters(
        ShuttleModel.Place("", "", false, "", "", UM_LAT, UM_LNG), ShuttleModel.Place("", "", false, "", "", lat, lng),
    ) ?: 0

    // ------------------------------------------------------------------ Google Maps links

    /**
     * The point in a Google Maps address: the place's own pin (!3d…!4d…) first, then the map centre (@lat,lng),
     * then q= / ll= / query= / destination=. Null when the address has none (a place id only).
     */
    fun pointIn(url: String): Pair<Double, Double>? {
        val u = try { URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        val n = """(-?\d{1,3}\.\d+)"""
        val pats = listOf(
            Regex("""!3d$n!4d$n"""),
            Regex("""@$n,$n"""),
            Regex("""[?&](?:q|ll|query|destination|center)=(?:loc:)?$n,\s*$n"""),
            Regex("""/maps/search/$n,\s*\+?$n"""),
        )
        for (p in pats) {
            val m = p.find(u) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return Pair(lat, lng)
        }
        return null
    }

    /** The place name in a Google Maps address (/maps/place/Name/...), or "". */
    fun nameIn(url: String): String =
        Regex("""/maps/place/([^/@?]+)""").find(url)?.groupValues?.get(1)
            ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }?.replace('+', ' ')?.trim().orEmpty()

    /** Only Google Maps addresses are opened (the shared text comes from another app). */
    private fun isGoogle(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "goo.gl" || h.endsWith(".goo.gl") || h == "google.com" || h.endsWith(".google.com") ||
            Regex("""(^|\.)google\.com?\.[a-z]{2}$""").containsMatchIn(h)
    }

    /** Follows a short link (maps.app.goo.gl/…) to the full address, on the calling (background) thread. */
    private fun resolve(start: String): String {
        var url = start
        repeat(6) {
            val u = URL(url)
            if (!isGoogle(u.host)) return url
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "SpectrumNotify")
                if (c.responseCode !in 300..399) return url
                url = URL(u, c.getHeaderField("Location") ?: return url).toString()
            } finally { c.disconnect() }
            if (pointIn(url) != null) return url
        }
        return url
    }

    /** What Google Maps shared: "Name\nhttps://maps.app.goo.gl/…" or the address alone. */
    fun fromShare(a: Activity, text: String, onSaved: (String) -> Unit) {
        val link = Regex("""https?://\S+""").find(text)?.value
        // the name is the text around the link (some apps put both on one line)
        val sharedName = text.replace(Regex("""https?://\S+"""), "\n").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (link == null || !isGoogle(try { URL(link).host } catch (_: Exception) { null })) {
            Toast.makeText(a, "That is not a Google Maps place. Drop the pin yourself.", Toast.LENGTH_LONG).show()
            a.editPlace(sharedName, null, null, null, onSaved)
            return
        }
        Toast.makeText(a, "Reading the place…", Toast.LENGTH_SHORT).show()
        Thread {
            val full = try { resolve(link) } catch (_: Exception) { link }
            val pt = pointIn(full)
            val name = sharedName.ifEmpty { nameIn(full) }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                when {
                    pt != null -> a.editPlace(name, pt.first, pt.second, null, onSaved)
                    // a link with a place id only: look the name up instead
                    name.isNotEmpty() -> a.searchMap(name) { found ->
                        val f = found.firstOrNull()
                        if (f == null) Toast.makeText(a, "Could not find where that is. Drop the pin yourself.", Toast.LENGTH_LONG).show()
                        a.editPlace(name, f?.lat, f?.lng, null, onSaved)
                    }
                    else -> {
                        Toast.makeText(a, "Could not read the place from the link. Drop the pin yourself.", Toast.LENGTH_LONG).show()
                        a.editPlace("", null, null, null, onSaved)
                    }
                }
            }
        }.start()
    }
}

/** One place found by the map search. */
class FoundPlace(val name: String, val detail: String, val lat: Double, val lng: Double)

/**
 * Searches places by name with Android's own place search (Google's data on phones with Google services; no key),
 * inside Malaysia, nearest to UM first. Calls back on the main thread; an empty list when nothing is found or the
 * phone has no place search.
 */
fun Activity.searchMap(q: String, onDone: (List<FoundPlace>) -> Unit) {
    if (!android.location.Geocoder.isPresent()) { onDone(emptyList()); return }
    Thread {
        val out = try {
            @Suppress("DEPRECATION")
            android.location.Geocoder(this, Locale.ENGLISH).getFromLocationName(q, 6, 0.8, 99.5, 7.5, 119.5).orEmpty()
                .filter { it.hasLatitude() && it.hasLongitude() }
                .map { ad ->
                    val line = ad.getAddressLine(0).orEmpty()
                    val name = ad.featureName?.takeIf { f -> f.isNotBlank() && f.any { it.isLetter() } } ?: line.substringBefore(',').ifEmpty { q }
                    FoundPlace(name, line.removePrefix(name).trim(',', ' '), ad.latitude, ad.longitude)
                }
                .sortedBy { MyPlaces.metersFromUm(it.lat, it.lng) }
        } catch (_: Exception) { emptyList() }
        runOnUiThread { if (!isFinishing) onDone(out) }
    }.start()
}

/** Your location now (a fix from the last 2 minutes, or a fresh one). Asks for permission first; then tap again. */
@android.annotation.SuppressLint("MissingPermission")
fun Activity.findLocation(requestCode: Int, onLoc: (android.location.Location) -> Unit) {
    val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
    val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
    val ok = android.content.pm.PackageManager.PERMISSION_GRANTED
    if (checkSelfPermission(fine) != ok && checkSelfPermission(coarse) != ok) {
        requestPermissions(arrayOf(fine, coarse), requestCode)
        return
    }
    val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
    val provider = providers.firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
    if (provider == null) {
        Toast.makeText(this, "Location is off. Turn it on in quick settings, then try again.", Toast.LENGTH_LONG).show()
        return
    }
    val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
        .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
        .minByOrNull { it.accuracy }
    if (recent != null) { onLoc(recent); return }
    Toast.makeText(this, "Finding where you are…", Toast.LENGTH_SHORT).show()
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
            if (loc != null) onLoc(loc)
            else Toast.makeText(this, "Could not find your location. Try again outdoors.", Toast.LENGTH_LONG).show()
        }
    } else {
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) = onLoc(loc)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }, android.os.Looper.getMainLooper())
    }
}

/**
 * Add (or move / rename, with [id]) one of your places: a map with a pin fixed in the middle, moved by dragging
 * the map, "I'm here", and the name. Starts at [lat]/[lng] when known, else at UM. [onSaved] gets the place id.
 */
fun Activity.editPlace(name: String, lat: Double?, lng: Double?, id: String?, onSaved: (String) -> Unit) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
    lateinit var nameField: android.widget.EditText
    val map = ShuttleMap(this) { }
    // find it by name first: most people do not know a place's spot on a map; dragging is only to fine-tune
    val search = col.field("", "Search a place, e.g. KL Sentral, Mid Valley")
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(results)
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var seq = 0
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) {
            val q = e?.toString()?.trim().orEmpty()
            val mine = ++seq
            results.removeAllViews()
            if (q.length < 3) return
            results.label("Searching…", Type.LABEL, Ui.DIM, 6)
            handler.postDelayed({
                if (mine == seq) searchMap(q) { found ->
                    if (mine != seq) return@searchMap
                    results.removeAllViews()
                    if (found.isEmpty()) results.label("Nothing found. Tap I'm here, or drag the map to the place.", Type.LABEL, Ui.DIM, 6)
                    found.take(5).forEach { f ->
                        results.addView(LinearLayout(this@editPlace).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            background = rippleBg(Ui.BAR, Ui.BORDER, this@editPlace, Radius.SM)
                            isClickable = true
                            addView(tv(f.name, Type.BODY, Ui.TEXT, true))
                            if (f.detail.isNotEmpty()) addView(tv(f.detail, Type.CAPTION, Ui.DIM).apply { maxLines = 2 })
                            setOnClickListener {
                                map.view.controller.setZoom(17.0)
                                map.view.controller.setCenter(GeoPoint(f.lat, f.lng))
                                if (nameField.text.isBlank()) nameField.setText(f.name)
                                results.removeAllViews()
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                                    .hideSoftInputFromWindow(search.windowToken, 0)
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                    }
                }
            }, 500)
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    col.label("The place is where the pin points. Drag the map to move it.", Type.CAPTION, Ui.DIM, 10)
    map.view.controller.setZoom(if (lat != null) 17.0 else 15.5)
    if (lat != null && lng != null) map.view.controller.setCenter(GeoPoint(lat, lng))
    val frame = FrameLayout(this)
    frame.addView(map.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // the pin's point is the map's centre: the icon sits above it
    frame.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_place)
        setColorFilter(Ui.RED)
        translationY = -dp(18).toFloat()
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
    col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(6) })
    col.btn("I'm here") {
        findLocation(43) { loc -> map.view.controller.animateTo(GeoPoint(loc.latitude, loc.longitude)); map.view.controller.setZoom(18.0) }
    }
    col.label("Name", Type.LABEL, Ui.TEXT, 12)
    nameField = col.field(name, "e.g. My house, Kolej 5 back gate")
    // a name but no spot yet ("Add "X" yourself"): look it up straight away
    if (lat == null && name.isNotEmpty()) search.setText(name)
    val dlg = AlertDialog.Builder(this, Ui.dialogTheme)
        .setTitle(if (id == null) "Add a place" else "Move or rename")
        // scrolls on small screens (the map keeps its own drags)
        .setView(android.widget.ScrollView(this).apply { addView(col) })
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel", null)
        .create()
    dlg.setOnDismissListener { map.onDestroy() }
    dlg.show()
    map.onResume()
    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val n = nameField.text.toString().trim()
        if (n.isEmpty()) { nameField.error = "Give it a name"; return@setOnClickListener }
        val c = map.view.mapCenter
        val far = MyPlaces.metersFromUm(c.latitude, c.longitude)
        val pid = MyPlaces.save(this, id, n, c.latitude, c.longitude)
        if (far > 5000) Toast.makeText(this, "Saved. It is ${"%.1f".format(far / 1000.0)} km from UM: the shuttle may not reach it, so trips there may be walks.", Toast.LENGTH_LONG).show()
        dlg.dismiss()
        onSaved(pid)
    }
}

/** Long-press on one of your places: move or rename it, or delete it. */
fun Activity.manageMyPlace(p: ShuttleModel.Place, onChanged: () -> Unit) {
    AlertDialog.Builder(this, Ui.dialogTheme)
        .setTitle(p.name)
        .setItems(arrayOf("Move or rename", "Delete")) { _, i ->
            if (i == 0) editPlace(p.name, p.lat, p.lng, p.id) { onChanged() }
            else { MyPlaces.delete(this, p.id); onChanged() }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
