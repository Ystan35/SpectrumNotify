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
        private var ro: State? = null
        private var roStamp = 0L

        /**
         * One shared copy for read-only hot paths (the link resolver ran once per class and parsed the whole file,
         * ~0.5 MB, each time); read again when the file changes. Never put() into it or save() it.
         */
        fun readOnly(ctx: Context): State = synchronized(this) {
            val f = File(ctx.applicationContext.filesDir, "state.json")
            val stamp = f.lastModified() * 31 + f.length()
            ro?.takeIf { stamp == roStamp } ?: State(ctx).also { ro = it; roStamp = stamp }
        }
    }
}
