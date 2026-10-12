package my.spectrum.notify

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/** Same idea as state.json in the Python bot: what was already seen/sent, plus cached calendar data. */
class State(ctx: Context) {
    private val f = File(ctx.applicationContext.filesDir, "state.json")
    val j: JSONObject = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }

    fun strings(k: String): MutableSet<String> {
        val a = j.optJSONArray(k) ?: return LinkedHashSet<String>()
        return LinkedHashSet(a.strs())
    }

    fun putStrings(k: String, v: Collection<String>) {
        j.put(k, jarr(v))
    }

    fun save() {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(f)
    }

    companion object {
        val lock = ReentrantLock()
    }
}
