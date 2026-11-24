package nl.home.alarmclock

import android.content.Context
import android.util.Log
import org.json.JSONArray

/** Alarms persisted as a JSON array in SharedPreferences. Only used from the main thread. */
class AlarmRepository(context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val alarms = LinkedHashMap<String, Alarm>()

    init {
        try {
            val arr = JSONArray(prefs.getString("alarms", "[]"))
            for (i in 0 until arr.length()) {
                val a = Alarm.fromJson(arr.getJSONObject(i))
                alarms[a.id] = a
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not load alarms", e)
        }
    }

    fun all(): List<Alarm> = alarms.values.toList()

    fun get(id: String): Alarm? = alarms[id]

    fun put(alarm: Alarm) {
        alarms[alarm.id] = alarm
        save()
    }

    fun remove(id: String): Boolean {
        val removed = alarms.remove(id) != null
        if (removed) save()
        return removed
    }

    fun clear() {
        alarms.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        alarms.values.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("alarms", arr.toString()).apply()
    }

    companion object {
        private const val TAG = "AlarmRepository"
    }
}
