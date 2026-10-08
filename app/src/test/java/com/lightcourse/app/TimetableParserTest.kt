package com.lightcourse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用 testdata/我的课表.xls（教务系统导出的列表格式）验证解析逻辑。
 * 仅走 POI 读取路径（.xls），不依赖 Android 运行时。
 */
class TimetableParserTest {

    private fun parseFixture(): Timetable {
        // Gradle 单测工作目录是 app/，项目根是其上级
        val f = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "testdata/我的课表.xls") }
            .firstOrNull { it.exists() }
            ?: error("找不到测试文件 testdata/我的课表.xls")
        val grid = ExcelParser.parse(f)
        return TimetableBuilder.build(grid) ?: error("未能从文件解析出课表")
    }

    @Test
    fun parsesCourseListFormat() {
        val t = parseFixture()
        // 固定显示周一~周日 7 列，周末没课也要有
        assertEquals((0..6).map { "周" + "一二三四五六日"[it] }, t.dayHeaders)
        // 节次数应为最大结束节次与 10 的较大者（即使没课也要显示到第 9-10 节）
        assertEquals(10, t.periods.size)
    }

    @Test
    fun spotsSingleWeekAndConsecutivePeriods() {
        val t = parseFixture()
        // 离散数学 星期二 1-2 节，无单双周标记
        val t2 = t.cells[0][1]
        assertNotNull(t2)
        assertEquals("离散数学", t2!!.name)
        assertEquals("东8", t2.room)
        assertEquals("冯琪", t2.teacher)
        assertEquals("1-15周", t2.weeks)
        // 计算机组成原理 星期四 5-6 节 且为单周
        val t45 = t.cells[4][3]
        assertNotNull(t45)
        assertEquals("计算机组成原理", t45!!.name)
        assertEquals("1-15周(单)", t45.weeks)
        // 连堂：5-6 节两格都有该课
        assertNotNull(t.cells[5][3])
    }

    @Test
    fun weekLogicFiltersSingleDoubleWeeks() {
        // 1-15周(单)：只在单周上课
        assertTrue(WeekLogic.matches("1-15周(单)", 5))
        assertTrue(!WeekLogic.matches("1-15周(单)", 4))
        // 1-15周(双)：只在双周上课
        assertTrue(WeekLogic.matches("1-15周(双)", 4))
        assertTrue(!WeekLogic.matches("1-15周(双)", 5))
        // 普通区间与区间外
        assertTrue(WeekLogic.matches("1-15周", 15))
        assertTrue(!WeekLogic.matches("1-8周", 9))
        assertTrue(!WeekLogic.matches("11-12周", 5))
        // 无周次信息 = 每周都有
        assertTrue(WeekLogic.matches("", 5))
        // 单个周次
        assertTrue(WeekLogic.matches("5周", 5))
        assertTrue(!WeekLogic.matches("5周", 6))
    }

    @Test
    fun defaultPeriodTimesMatchSchoolSchedule() {
        val t = Timetable.DEFAULT_PERIOD_TIMES
        // 下午 5 节 14:30 起、7 节 16:20 起（课间 10 分钟）；晚上 9 节 19:00 起
        assertEquals("14:30" to "15:15", t[4])
        assertEquals("15:25" to "16:10", t[5])
        assertEquals("16:20" to "17:05", t[6])
        assertEquals("17:15" to "18:00", t[7])
        assertEquals("19:00" to "19:45", t[8])
    }
}
