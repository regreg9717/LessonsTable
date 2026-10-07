package com.lightcourse.app

/** 按课程名哈希分配柔和的底色 + 深色文字 */
object CourseColors {

    // 柔和粉彩色（背景 ARGB）与对应的深色文字
    private val PAIRS = listOf(
        0xFFE3F2FD.toInt() to 0xFF1565C0.toInt(), // 蓝
        0xFFE8F5E9.toInt() to 0xFF2E7D32.toInt(), // 绿
        0xFFFFF3E0.toInt() to 0xFFE65100.toInt(), // 橙
        0xFFF3E5F5.toInt() to 0xFF6A1B9A.toInt(), // 紫
        0xFFE0F7FA.toInt() to 0xFF00838F.toInt(), // 青
        0xFFFCE4EC.toInt() to 0xFFAD1457.toInt(), // 玫红
        0xFFFBE9E7.toInt() to 0xFFBF360C.toInt(), // 柿红
        0xFFF1F8E9.toInt() to 0xFF558B2F.toInt(), // 草绿
        0xFFEDE7F6.toInt() to 0xFF4527A0.toInt(), // 靛蓝
        0xFFFFFDE7.toInt() to 0xFF9E9D24.toInt(), // 橄榄
    )

    fun forCourse(name: String): Pair<Int, Int> {
        var h = 0
        for (ch in name) h = h * 31 + ch.code
        return PAIRS[(h and Int.MAX_VALUE) % PAIRS.size]
    }
}
