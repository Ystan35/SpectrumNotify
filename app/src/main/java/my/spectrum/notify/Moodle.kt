package my.spectrum.notify

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MoodleException(msg: String) : Exception(msg)

/** Minimal Moodle web-service client. The token never leaves the device except to your own Spectrum site. */
class Moodle(private val site: String, private val token: String) {

    fun call(fn: String, params: Map<String, Any> = emptyMap()): Any {
        val sb = StringBuilder()
        fun add(k: String, v: Any) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v.toString(), "UTF-8"))
        }
        add("wstoken", token)
        add("wsfunction", fn)
        add("moodlewsrestformat", "json")
        for ((k, v) in params) {
            if (v is List<*>) v.forEachIndexed { i, x -> add("$k[$i]", x ?: "") } else add(k, v)
        }
        val conn = URL("$site/webservice/rest/server.php").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(sb.toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }.trim()
            val data: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            if (data is JSONObject && data.has("exception")) {
                throw MoodleException("$fn: ${data.str("errorcode")}: ${data.str("message")}")
            }
            return data
        } finally {
            conn.disconnect()
        }
    }

    fun obj(fn: String, params: Map<String, Any> = emptyMap()): JSONObject =
        call(fn, params) as? JSONObject ?: JSONObject()

    fun arr(fn: String, params: Map<String, Any> = emptyMap()): JSONArray =
        call(fn, params) as? JSONArray ?: JSONArray()
}
