package com.lightcourse.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 一个格子里的课程信息 */
data class CourseCell(
    val name: String,
    val room: String = "",
    val teacher: String = "",
    val weeks: String = "",   // 上课周次，如 “1-15周(单)”
    val custom: Boolean = false, // 手动添加的值班等自定义项（强制紫色）
) {
    /** “单”/“双”周短标记（用于卡片上），无则为空 */
    val weekTag: String get() = when {
        custom -> ""
        weeks.contains("单") -> "单周"
        weeks.contains("双") -> "双周"
        else -> ""
    }
}

/** 一节课的名称与起止时间 */
data class Period(val label: String, val start: String, val end: String)

/** 手动添加的自定义事项（值班） */
data class CustomEntry(
    val day: Int,      // 0=周一 .. 6=周日
    val period: Int,   // 1-based 节次
    val span: Int,     // 连续节数
    val name: String,
    val room: String,
)

/** 整张课表 */
data class Timetable(
    val title: String,
    val dayHeaders: List<String>,        // 例如 周一..周五
    val periods: List<Period>,
    val cells: List<List<CourseCell?>>,  // cells[节次][天]
    val custom: List<CustomEntry> = emptyList(),
) {
    companion object {
        /**
         * 默认节次时间（45 分钟 + 10 分钟课间）：
         * 上午 1-2 节 08:00 起、3-4 节 10:00 起；下午 5-6 节 14:30 起、7-8 节接排；
         * 晚上 9-10 节 19:00 起。可在“节次时间”里自行修改。
         */
        val DEFAULT_PERIOD_TIMES: List<Pair<String, String>> = listOf(
            "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
            "14:30" to "15:15", "15:25" to "16:10", "16:20" to "17:05", "17:15" to "18:00",
            "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35", "21:45" to "22:30",
        )
    }
}

/** 基于 SharedPreferences + JSON 的本地存储 */
object TimetableStore {
    private const val PREFS = "timetable"
    private const val KEY_DATA = "json"

    fun load(ctx: android.content.Context): Timetable? {
        val s = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_DATA, null) ?: return null
        return runCatching { fromJson(JSONObject(s)) }.getOrNull()
    }

    fun save(ctx: android.content.Context, t: Timetable) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, toJson(t).toString()).apply()
    }

    fun clear(ctx: android.content.Context) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().remove(KEY_DATA).apply()
    }

    /** 学期第 1 周的周一；默认 2026-09-07（该学期今天 2026-10-07 恰为第 5 周） */
    val DEFAULT_SEMESTER_START_MILLIS: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 7, 0, 0, 0)
    }.timeInMillis

    fun loadSemesterStart(ctx: android.content.Context): Long =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getLong("semester_start", DEFAULT_SEMESTER_START_MILLIS)

    fun saveSemesterStart(ctx: android.content.Context, millis: Long) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putLong("semester_start", millis).apply()
    }

    fun loadPeriodTimes(ctx: android.content.Context): MutableList<Pair<String, String>> {        val s = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("period_times", null) ?: return Timetable.DEFAULT_PERIOD_TIMES.toMutableList()
        val list = mutableListOf<Pair<String, String>>()
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(o.getString("s") to o.getString("e"))
            }
        }
        return list.ifEmpty { Timetable.DEFAULT_PERIOD_TIMES.toMutableList() }
    }

    fun savePeriodTimes(ctx: android.content.Context, times: List<Pair<String, String>>) {
        val arr = JSONArray()
        times.forEach { arr.put(JSONObject().put("s", it.first).put("e", it.second)) }
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString("period_times", arr.toString()).apply()
    }

    private fun toJson(t: Timetable): JSONObject {
        val days = JSONArray(t.dayHeaders)
        val periods = JSONArray()
        t.periods.forEach { p ->
            periods.put(JSONObject().put("label", p.label).put("start", p.start).put("end", p.end))
        }
        val rows = JSONArray()
        t.cells.forEach { row ->
            val r = JSONArray()
            row.forEach { c ->
                r.put(if (c == null) JSONObject.NULL else
                    JSONObject().put("n", c.name).put("room", c.room)
                        .put("t", c.teacher).put("w", c.weeks).put("c", c.custom))
            }
            rows.put(r)
        }
        val custom = JSONArray()
        t.custom.forEach { e ->
            custom.put(JSONObject().put("d", e.day).put("p", e.period)
                .put("s", e.span).put("n", e.name).put("r", e.room))
        }
        return JSONObject().put("title", t.title).put("days", days)
            .put("periods", periods).put("cells", rows).put("custom", custom)
    }

    private fun fromJson(o: JSONObject): Timetable {
        val days = mutableListOf<String>()
        val dArr = o.getJSONArray("days")
        for (i in 0 until dArr.length()) days.add(dArr.getString(i))
        val periods = mutableListOf<Period>()
        val pArr = o.getJSONArray("periods")
        for (i in 0 until pArr.length()) {
            val p = pArr.getJSONObject(i)
            periods.add(Period(p.optString("label"), p.optString("start"), p.optString("end")))
        }
        val rows = mutableListOf<List<CourseCell?>>()
        val rArr = o.getJSONArray("cells")
        for (i in 0 until rArr.length()) {
            val row = mutableListOf<CourseCell?>()
            val cArr = rArr.getJSONArray(i)
            for (j in 0 until cArr.length()) {
                row.add(if (cArr.isNull(j)) null else run {
                    val c = cArr.getJSONObject(j)
                    CourseCell(c.optString("n"), c.optString("room"), c.optString("t"),
                        c.optString("w"), c.optBoolean("c"))
                })
            }
            rows.add(row)
        }
        val custom = mutableListOf<CustomEntry>()
        val eArr = o.optJSONArray("custom")
        if (eArr != null) {
            for (i in 0 until eArr.length()) {
                val e = eArr.getJSONObject(i)
                custom.add(CustomEntry(e.optInt("d"), e.optInt("p"),
                    e.optInt("s", 1), e.optString("n"), e.optString("r")))
            }
        }
        return Timetable(o.optString("title"), days, periods, rows, custom)
    }
}
