package com.lightcourse.app

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.File
import java.io.InputStream

/** 统一入口：按扩展名分发到 xlsx 手写解析器 / POI HSSF 解析器 */
object ExcelParser {

    fun parse(file: File): List<List<String?>> {
        // 扩展名不可靠（有的 xls 其实是 xlsx），失败时自动换另一种解析器
        val grid = if (file.extension.lowercase() == "xls") {
            runCatching { parseXls(file.inputStream()) }
                .getOrElse { XlsxParser.parse(file.inputStream()) }
        } else {
            runCatching { XlsxParser.parse(file.inputStream()) }
                .getOrElse { parseXls(file.inputStream()) }
        }
        return trimGrid(grid)
    }

    /** 老式 .xls（BIFF8）用 Apache POI 的 HSSF 读取 */
    private fun parseXls(input: InputStream): List<List<String?>> {
        HSSFWorkbook(input).use { wb ->
            val sheet = wb.getSheetAt(0)
            val formatter = org.apache.poi.ss.usermodel.DataFormatter()
            val grid = mutableListOf<MutableList<String?>>()
            for (r in 0 until sheet.physicalNumberOfRows) {
                val row = sheet.getRow(r) ?: continue
                val list = mutableListOf<String?>()
                for (c in row) {
                    // 补齐跳过的空列
                    while (list.size < c.columnIndex) list.add(null)
                    val text = formatter.formatCellValue(c).trim()
                    list.add(text.ifEmpty { null })
                }
                grid.add(list)
            }
            return grid
        }
    }

    /** 去掉外围全空行列 */
    private fun trimGrid(grid: List<List<String?>>): List<List<String?>> {
        val rows = grid.filter { it.any { c -> !c.isNullOrBlank() } }
        if (rows.isEmpty()) return emptyList()
        val maxCol = rows.maxOf { it.size }
        var lastUsedCol = -1
        for (col in 0 until maxCol) {
            if (rows.any { col < it.size && !it[col].isNullOrBlank() }) lastUsedCol = col
        }
        return rows.map { row ->
            (0..lastUsedCol).map { row.getOrNull(it) }
        }
    }
}

/**
 * 把二维表格解释成课表。支持两种格式：
 *
 * 1. 教务系统导出的列表格式（优先识别，参考用户的“我的课表.xls”）：
 *    表头含“课程名”“上课星期”“开始节次”“结束节次”“上课周次”“上课教师”“教室名称”等列，
 *    每行一门课的排课记录，支持连堂（3-4 节）与单双周（如 1-15周(单)）。
 *
 * 2. 周视图网格格式（备用/手动模板）：
 *    表头为 周一~周日，左边一列节次名称，单元格“课程名@教室@教师”。
 */
object TimetableBuilder {

    private val WEEK_ORDER = "一二三四五六日"

    fun build(grid: List<List<String?>>): Timetable? {
        if (grid.isEmpty()) return null
        // 在前 5 行里找列表格式表头，优先
        for (r in 0 until minOf(5, grid.size)) {
            val cols = matchCourseListHeader(grid[r])
            if (cols != null) return fromCourseList(grid, r, cols)
        }
        return fromGrid(grid)
    }

    // ---------- 格式一：教务系统列表 ----------

    private data class ListCols(
        val name: Int, val day: Int, val start: Int, val end: Int,
        val weeks: Int, val teacher: Int, val room: Int,
    )

    private fun matchCourseListHeader(row: List<String?>): ListCols? {
        fun find(pred: (String) -> Boolean): Int =
            row.indexOfFirst { it != null && pred(it.replace(" ", "")) }
                .let { if (it >= 0) it else -1 }
        val name = find { it == "课程名" || it.contains("课程名称") }
        if (name < 0) return null
        val day = find { it.contains("上课星期") || (it.contains("星期") && it.length <= 6) }
        if (day < 0) return null
        val start = find { it.contains("开始节次") || it == "起始节次" }
        if (start < 0) return null
        val end = find { it.contains("结束节次") }
        if (end < 0) return null
        val weeks = find { it.contains("周次") }
        val teacher = find { it.contains("教师") || it.contains("老师") }
        val room = find { it.contains("教室") || it.contains("地点") }
        return ListCols(name, day, start, end, weeks, teacher, room)
    }

    private fun fromCourseList(grid: List<List<String?>>, headerRow: Int, cols: ListCols): Timetable? {
        data class Entry(val dayIdx: Int, val start: Int, val end: Int, val cell: CourseCell)

        val entries = mutableListOf<Entry>()
        for (r in headerRow + 1 until grid.size) {
            val row = grid[r]
            val name = row.getOrNull(cols.name)?.takeIf { it.isNotBlank() } ?: continue
            val dayIdx = dayIndexOf(row.getOrNull(cols.day) ?: "") ?: continue
            val start = parseIntOr1(row.getOrNull(cols.start))
            val end = parseIntOr1(row.getOrNull(cols.end)).coerceAtLeast(start)
            val weeks = row.getOrNull(cols.weeks) ?: ""
            val teacher = row.getOrNull(cols.teacher) ?: ""
            val room = row.getOrNull(cols.room) ?: ""
            entries.add(Entry(dayIdx, start, end, CourseCell(name, room, teacher, weeks)))
        }
        if (entries.isEmpty()) return null

        // 固定 7 列（周一~周日），周末没课也要显示；节次至少 10 节（含 9-10 节晚课）
        val dayHeaders = (0..6).map { "周" + WEEK_ORDER[it] }
        val periodCount = maxOf(10, entries.maxOf { it.end }).coerceAtMost(15)
        val times = Timetable.DEFAULT_PERIOD_TIMES

        val periods = (0 until periodCount).map { i ->
            val t = times.getOrNull(i) ?: ("" to "")
            Period("第${i + 1}节", t.first, t.second)
        }
        val grid2 = List(periodCount) { MutableList<CourseCell?>(7) { null } }
        for (e in entries) {
            for (p in (e.start - 1)..(e.end - 1)) {
                if (p in grid2.indices) grid2[p][e.dayIdx] = e.cell
            }
        }
        return Timetable("", dayHeaders, periods, grid2)
    }

    /** “星期二”/“周二”/“二” -> 0..6 */
    private fun dayIndexOf(v: String): Int? {
        val m = Regex("(?:星期|周)?([一二三四五六日天])").find(v.replace(" ", "")) ?: return null
        val ch = m.groupValues[1].let { if (it == "天") "日" else it }
        return WEEK_ORDER.indexOf(ch).takeIf { it >= 0 }
    }

    private fun parseIntOr1(v: String?): Int =
        v?.trim()?.removeSuffix(".0")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    // ---------- 格式二：周视图网格 ----------

    private fun fromGrid(grid: List<List<String?>>): Timetable? {
        var headerRowIdx = -1
        var dayCols = emptyList<Pair<Int, Int>>() // col -> dayIdx

        for (r in 0 until minOf(5, grid.size)) {
            val found = mutableListOf<Pair<Int, Int>>()
            for (c in grid[r].indices) {
                val v = grid[r][c] ?: continue
                if (v.length > 3) continue
                val d = dayIndexOf(v) ?: continue
                found.add(c to d)
            }
            if (found.size >= 2 && found.all { (_, d) -> d < 7 }) {
                headerRowIdx = r
                dayCols = found
                break
            }
        }

        // 固定 7 列（周一~周日）
        val dayHeaders = (0..6).map { "周" + WEEK_ORDER[it] }
        val labelCol: Int
        val firstDataRow: Int

        if (headerRowIdx >= 0) {
            labelCol = dayCols.minOf { it.first } - 1
            firstDataRow = headerRowIdx + 1
        } else {
            // 无表头：第 0 列为节次，第 1 列起按周一..周五处理
            labelCol = 0
            firstDataRow = 0
        }

        val periods = mutableListOf<Period>()
        val cells = mutableListOf<MutableList<CourseCell?>>()
        val periodTimes = Timetable.DEFAULT_PERIOD_TIMES

        for (r in firstDataRow until grid.size) {
            val row = grid[r]
            val label = if (labelCol >= 0) row.getOrNull(labelCol)?.takeIf { it.isNotBlank() } else null
            var hasCourse = false
            val courseRow = mutableListOf<CourseCell?>()
            for (d in 0..6) {
                val col: Int? = if (headerRowIdx >= 0) dayCols.firstOrNull { it.second == d }?.first
                else labelCol + 1 + d
                val raw = col?.let { row.getOrNull(it) }?.takeIf { it.isNotBlank() }
                val cell = raw?.let { parseGridCell(it) }
                courseRow.add(cell)
                if (cell != null) hasCourse = true
            }
            if (!hasCourse && label == null) continue // 整行空
            val idx = periods.size
            val time = periodTimes.getOrNull(idx) ?: ("" to "")
            val periodLabel = label ?: "第${idx + 1}节"
            periods.add(Period(periodLabel, time.first, time.second))
            cells.add(courseRow)
        }

        // 保证至少显示 10 节（第 9-10 节晚课也要有行）
        while (periods.size < 10) {
            val idx = periods.size
            val time = periodTimes.getOrNull(idx) ?: ("" to "")
            periods.add(Period("第${idx + 1}节", time.first, time.second))
            cells.add(MutableList(7) { null })
        }

        if (cells.isEmpty() || cells.all { row -> row.all { it == null } }) return null

        return Timetable("", dayHeaders, periods, cells)
    }

    /** 把 "课程名@教室@教师" / 换行分隔的内容拆开 */
    private fun parseGridCell(raw: String): CourseCell? {
        val parts = raw.replace('＠', '@')
            .split('@', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val name = parts[0]
        if (name.isBlank()) return null
        val room = parts.getOrNull(1)?.takeIf { !it.equals(name, true) } ?: ""
        val teacher = parts.getOrNull(2) ?: ""
        return CourseCell(name, room, teacher)
    }
}
