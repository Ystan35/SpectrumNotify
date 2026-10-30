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

private val ENTITY = Regex("&(?:#(\\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|(amp|lt|gt|quot|apos|nbsp));")

/** Decodes HTML entities from Spectrum, named and numeric ("&#38;" -> "&"), in one pass so nothing is decoded twice. */
fun unesc(s: String?): String = ENTITY.replace(s ?: "") { m ->
    val (dec, hex, name) = m.destructured
    when {
        dec.isNotEmpty() || hex.isNotEmpty() -> {
            val cp = if (dec.isNotEmpty()) dec.toInt() else hex.toInt(16)
            if (cp == 0xA0) " " else if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else m.value
        }
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        else -> " "
    }
}

fun clean(text: String?, n: Int = 60): String {
    val t = unesc((text ?: "").replace(Regex("\\s+"), " ")).trim()
    return if (t.length <= n) t else t.substring(0, n - 1) + "…"
}

/** Every UM time is Malaysian time, whatever zone the phone is set to (Spectrum instants and the UM timetable agree). */
fun zone(): ZoneId = MYT
private val MYT: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
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
