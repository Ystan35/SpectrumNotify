package my.spectrum.notify

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strs(): List<String> = (0 until length()).map { optString(it) }
fun jarr(items: Collection<String>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }
fun jobjs(items: List<JSONObject>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }

fun unesc(s: String?): String = (s ?: "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
    .replace("&amp;", "&")

fun clean(text: String?, n: Int = 60): String {
    val t = unesc((text ?: "").replace(Regex("\\s+"), " ")).trim()
    return if (t.length <= n) t else t.substring(0, n - 1) + "…"
}

fun zone(): ZoneId = ZoneId.systemDefault()
fun localDt(ts: Long): ZonedDateTime = Instant.ofEpochSecond(ts).atZone(zone())
fun fmtTime(ts: Long): String =
    localDt(ts).format(DateTimeFormatter.ofPattern("EEE dd MMM, HH:mm", Locale.ENGLISH))
fun hhmm(ts: Long): String = localDt(ts).format(DateTimeFormatter.ofPattern("HH:mm"))

fun parseHm(s: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    return if (h in 0..23 && mi in 0..59) Pair(h, mi) else null
}
