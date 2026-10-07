package com.lightcourse.app

/** 判断某节课的“上课周次”（如 “1-15周(单)”）是否在第 week 周上课 */
object WeekLogic {

    fun matches(weeks: String, week: Int): Boolean {
        val w = weeks.replace(" ", "").replace("第", "")
        if (w.isEmpty() || !Regex("\\d").containsMatchIn(w)) return true // 无周次信息 = 每周都有
        val parity = when {
            w.contains("单") -> 1
            w.contains("双") -> 0
            else -> -1
        }
        fun ok() = parity < 0 || week % 2 == parity

        // 形如 1-15周 / 1-15周(单)
        var hasRange = false
        Regex("(\\d+)-(\\d+)").findAll(w).forEach { m ->
            hasRange = true
            if (week >= m.groupValues[1].toInt() && week <= m.groupValues[2].toInt()) return ok()
        }
        if (hasRange) return false

        // 单个周次：如 “5周” 或 “1,3,5周”
        Regex("(\\d+)").findAll(w).forEach { m ->
            if (m.groupValues[1].toInt() == week) return ok()
        }
        return false
    }
}
