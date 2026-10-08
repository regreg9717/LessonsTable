package com.lightcourse.app

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var timetable: Timetable? = null
    /** -1 = 跟随当前周；否则为用户用箭头切到的周数 */
    private var displayWeek = -1

    private lateinit var contentRoot: LinearLayout

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
        }

        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.app_name)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
            setTitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
        }
        setSupportActionBar(toolbar)
        contentRoot.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        // 父按钮：点击展开「导入课表 / 添加值班 / 删除值班」三个子按钮
        val fabs = listOf(
            ExtendedFloatingActionButton(this).apply {
                text = getString(R.string.import_timetable)
                textSize = 13f
                visibility = View.GONE
                setOnClickListener { expand = false; syncFab(); pickFile.launch(FILE_EXCEL) }
            },
            ExtendedFloatingActionButton(this).apply {
                text = getString(R.string.add_duty)
                textSize = 13f
                visibility = View.GONE
                setOnClickListener { expand = false; syncFab(); showAddDutyDialog() }
            },
            ExtendedFloatingActionButton(this).apply {
                text = getString(R.string.delete_duty)
                textSize = 13f
                visibility = View.GONE
                setOnClickListener { expand = false; syncFab(); showDeleteDutyDialog() }
            },
        )
        val fabMain = ExtendedFloatingActionButton(this).apply {
            text = getString(R.string.more_actions)
            setOnClickListener { expand = !expand; syncFab() }
        }

        val fabColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        // 统一样式：主题绿底白字、胶囊圆角、低高度柔和阴影
        fun styleFab(f: ExtendedFloatingActionButton, elev: Int) {
            f.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
            f.setTextColor(0xFFFFFFFF.toInt())
            f.shapeAppearanceModel = f.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(dp(26).toFloat())
                .build()
            f.elevation = dp(elev).toFloat()
        }
        fabs.forEach { styleFab(it, 1) }
        styleFab(fabMain, 2)
        fabs.forEach {
            fabColumn.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, 0, dp(10)) })
        }
        fabColumn.addView(fabMain, LinearLayout.LayoutParams(WRAP, WRAP))

        fabSync = {
            fabs.forEach { it.visibility = if (expand) View.VISIBLE else View.GONE }
            fabMain.text = getString(if (expand) R.string.collapse_actions else R.string.more_actions)
        }

        val fabParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(16), dp(20))
        }

        val frame = FrameLayout(this).apply {
            addView(contentRoot, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fabColumn, fabParams)
        }
        setContentView(frame)

        timetable = TimetableStore.load(this)
        render()
    }

    /** 子按钮展开状态与显隐同步 */
    private var expand = false
    private lateinit var fabSync: () -> Unit

    private fun syncFab() = fabSync.invoke()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export_template -> {
            TemplateExporter.share(this, TemplateExporter.export(this))
            true
        }
        R.id.action_set_week_start -> { showSetWeekStartDialog(); true }
        R.id.action_period_times -> { showPeriodTimesDialog(); true }
        R.id.action_help -> { showHelpDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importFrom(uri: android.net.Uri) {
        val name = queryName(uri) ?: "import.xlsx"
        val cache = File(cacheDir, "import_$name")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { input.copyTo(it) }
            } ?: return
            val grid = ExcelParser.parse(cache)
            val parsed = TimetableBuilder.build(grid)
            if (parsed == null) {
                toast(getString(R.string.parse_failed))
            } else {
                // 重新导入课表时保留已手动添加的值班
                val keptCustom = timetable?.custom ?: emptyList()
                timetable = syncTimes(parsed).copy(custom = keptCustom)
                displayWeek = -1
                TimetableStore.save(this, timetable!!)
                render()
                toast(getString(R.string.import_ok))
            }
        } catch (e: Exception) {
            toast(getString(R.string.parse_failed))
        } finally {
            cache.delete()
        }
    }

    /** 用本地保存的节次时间覆盖解析出的默认时间 */
    private fun syncTimes(t: Timetable): Timetable {
        val saved = TimetableStore.loadPeriodTimes(this)
        if (saved.size < t.periods.size) return t
        val periods = t.periods.mapIndexed { i, p -> p.copy(start = saved[i].first, end = saved[i].second) }
        return t.copy(periods = periods)
    }

    // ---------- 周次 ----------

    private fun shownWeek(): Int = if (displayWeek == -1) currentWeek() else displayWeek

    private fun currentWeek(): Int {
        val today = todayNormalized()
        val start = TimetableStore.loadSemesterStart(this)
        val diffDays = (today.timeInMillis - start) / DAY_MILLIS
        return ((diffDays / 7).toInt() + 1).coerceAtLeast(1)
    }

    private fun mondayOf(week: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = TimetableStore.loadSemesterStart(this@MainActivity)
            add(Calendar.DATE, (week - 1) * 7)
        }

    /** 把任意日期归到该周的周一 00:00 */
    private fun normalizeToMonday(c: Calendar) {
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val diff = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        c.add(Calendar.DATE, -diff)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    }

    private fun todayNormalized(): Calendar =
        Calendar.getInstance().apply { normalizeToMonday(this) }

    /** 今天是周几（周一=0..周日=6） */
    private fun todayDayIndex(): Int =
        (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    // ---------- UI 渲染 ----------

    private fun render() {
        contentRoot.removeViews(1, contentRoot.childCount - 1)
        val t = timetable
        if (t == null) {
            contentRoot.addView(emptyView(), LinearLayout.LayoutParams(MATCH, MATCH))
            supportActionBar?.subtitle = null
            return
        }
        supportActionBar?.subtitle = todaySubtitle()
        val week = shownWeek()

        contentRoot.addView(buildWeekBar(week), LinearLayout.LayoutParams(MATCH, WRAP))
        contentRoot.addView(buildDayHeader(t, week), LinearLayout.LayoutParams(MATCH, WRAP))
        contentRoot.addView(buildGrid(t, week), LinearLayout.LayoutParams(MATCH, MATCH))
    }

    /** 「◀ 第x周 ▶」切换条；点中间回到本周 */
    private fun buildWeekBar(week: Int): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val cur = currentWeek()

        fun arrow(text: String, delta: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(dp(28), dp(6), dp(28), dp(6))
            setTextColor(ContextCompat.getColor(context, R.color.text_sub))
            setOnClickListener {
                displayWeek = (week + delta).coerceIn(1, 30)
                render()
            }
        }

        val tvWeek = TextView(this).apply {
            text = "第${week}周"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (week == cur) R.color.text_main else R.color.primary
                )
            )
            setOnClickListener { displayWeek = -1; render() }
        }
        bar.addView(arrow("◀", -1))
        bar.addView(tvWeek)
        bar.addView(arrow("▶", 1))
        return bar
    }

    /** 星期表头：周X + 该周日期，今天高亮 */
    private fun buildDayHeader(t: Timetable, week: Int): View {
        val labelW = dp(32)
        val colW = (resources.displayMetrics.widthPixels - labelW) / 7
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(this), LinearLayout.LayoutParams(labelW, dp(46)))
        val monday = mondayOf(week)
        val today = Calendar.getInstance()
        for (d in 0..6) {
            val date = (monday.clone() as Calendar).apply { add(Calendar.DATE, d) }
            val isToday = isSameDay(date, today)
            val name = t.dayHeaders.getOrNull(d) ?: "周${"一二三四五六日"[d]}"

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val tvName = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                if (isToday) {
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackground(roundedColor(COLOR_TODAY_EMPTY, dp(13)))
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                } else {
                    setTextColor(ContextCompat.getColor(context, R.color.text_main))
                }
            }
            val tvDate = TextView(this).apply {
                text = SimpleDateFormat("M/d", Locale.CHINA).format(date.time)
                textSize = 9f
                gravity = Gravity.CENTER
                if (isToday) {
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(COLOR_TODAY_EMPTY)
                } else {
                    setTextColor(ContextCompat.getColor(context, R.color.text_sub))
                }
            }
            box.addView(tvName)
            box.addView(tvDate)
            header.addView(box, LinearLayout.LayoutParams(colW, dp(46)))
        }
        return header
    }

    /** 某周实际要显示的格子：解析课表按周次过滤 + 手动添加的值班覆盖 */
    private fun effectiveCells(t: Timetable, week: Int): List<MutableList<CourseCell?>> {
        val eff = t.cells.map { row ->
            row.map { c -> c?.takeIf { WeekLogic.matches(it.weeks, week) } }.toMutableList()
        }
        for (e in t.custom) {
            if (e.day !in 0..6) continue
            val cell = CourseCell(e.name, e.room, custom = true)
            for (i in 0 until e.span) {
                val p = e.period - 1 + i
                if (p in eff.indices) eff[p][e.day] = cell
            }
        }
        return eff
    }

    /** 课表主体：节次列 + 7 天列（每列独立纵向堆叠，连堂课合并为一张大卡），
     *  大节分界线用整宽覆盖层画成一条贯通的直线。
     *  所有列每行严格 rowH 高（间距在格子容器内部），保证分隔线与卡片永不错位 */
    private fun buildGrid(t: Timetable, week: Int): View {
        val rowH = dp(58)
        val labelW = dp(32)
        val colW = (resources.displayMetrics.widthPixels - labelW) / 7
        val todayIdx = todayDayIndex()
        val nPeriods = t.periods.size
        val cells = effectiveCells(t, week)

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 节次序号列
        val labelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (p in 0 until nPeriods) {
            val num = Regex("\\d+").find(t.periods[p].label)?.value ?: "${p + 1}"
            val tv = TextView(this).apply {
                text = num
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_sub))
            }
            labelCol.addView(tv, LinearLayout.LayoutParams(MATCH, rowH))
        }
        content.addView(labelCol, LinearLayout.LayoutParams(labelW, WRAP))

        // 7 天课程列
        for (d in 0..6) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            var p = 0
            while (p < nPeriods) {
                val cell = cells.getOrNull(p)?.getOrNull(d)
                var span = 1
                if (cell != null) {
                    // 连堂合并：后续节次是同一门课（含周次）且不跨大节边界
                    while (p + span < nPeriods && !isGroupBoundary(p + span, nPeriods) &&
                        cells.getOrNull(p + span)?.getOrNull(d) == cell
                    ) span++
                }
                col.addView(courseCellView(cell, span, rowH, colW, d == todayIdx, p, d))
                p += span
            }
            content.addView(col, LinearLayout.LayoutParams(colW, WRAP))
        }

        // 覆盖层：大节分界处画整宽贯通直线（2|3、4|5、6|7 节之间）
        val overlay = FrameLayout(this)
        for (p in 0 until nPeriods) {
            if (!isGroupBoundary(p, nPeriods)) continue
            val line = View(this).apply {
                setBackgroundColor(0xFFBFC8C4.toInt())
            }
            overlay.addView(
                line,
                FrameLayout.LayoutParams(MATCH, dp(1)).apply {
                    topMargin = p * rowH
                }
            )
        }

        val layered = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(WRAP, WRAP))
            addView(overlay, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val vScroll = ScrollView(this)
        vScroll.addView(layered, LinearLayout.LayoutParams(MATCH, WRAP))
        return vScroll
    }

    /** 大节分界：第2|3、4|5、6|7节之间（p 为 0-based 节次序号） */
    private fun isGroupBoundary(p: Int, nPeriods: Int): Boolean = p > 0 && p % 2 == 0 && p < nPeriods

    private fun courseCellView(
        cell: CourseCell?, span: Int, rowH: Int, colW: Int, isTodayCol: Boolean, p: Int, d: Int,
    ): View {
        // 容器不设外边距（保证各列行高严格对齐），留白由内部卡片的边距实现
        val container = FrameLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(colW, span * rowH)
        if (cell == null) {
            return container
        }

        // 配色：值班强制深紫白字；今天列用课程色的深色版本；其他列用课程色的浅色版本
        val pair = CourseColors.forCourse(cell.name)
        val bg = when {
            cell.custom -> COLOR_CUSTOM
            isTodayCol -> pair.second
            else -> pair.first
        }
        val fg = if (cell.custom || isTodayCol) 0xFFFFFFFF.toInt() else pair.second
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackground(roundedColor(bg, dp(8)))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val nameTv = TextView(this).apply {
            text = cell.name
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 10
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        card.addView(nameTv)

        val info = listOf(cell.room, cell.weekTag).filter { it.isNotEmpty() }.joinToString(" ")
        if (info.isNotEmpty()) {
            val roomTv = TextView(this).apply {
                text = info
                textSize = 9f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(fg)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            card.addView(roomTv)
        }
        container.addView(card, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        container.setOnClickListener { showCellDialog(cell, p, d, span) }
        return container
    }

    private fun emptyView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = getString(R.string.empty_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = getString(R.string.empty_hint)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_sub))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(title)
        box.addView(hint)
        return box
    }

    // ---------- 对话框 ----------

    /** 课程详情：卡片式对话框，头部为课程配色，时间显示整个大节（含课间休息） */
    private fun showCellDialog(c: CourseCell, periodIdx: Int, dayIdx: Int, span: Int) {
        val t = timetable ?: return
        val day = t.dayHeaders.getOrNull(dayIdx) ?: ""
        val first = t.periods.getOrNull(periodIdx)
        val last = t.periods.getOrNull(periodIdx + span - 1) ?: first

        val periodText = if (span > 1) "第${periodIdx + 1}-${periodIdx + span}节" else "第${periodIdx + 1}节"
        val timeText = when {
            first == null || first.start.isBlank() -> ""
            span > 1 && last != null -> "${first.start} - ${last.end}"
            else -> "${first.start} - ${first.end}"
        }
        val subtitle = listOf(day, periodText, timeText).filter { it.isNotEmpty() }.joinToString("  ")

        val headerColor = if (c.custom) COLOR_CUSTOM else CourseColors.forCourse(c.name).second

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }

        fun row(label: String, value: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_sub))
                minWidth = dp(52)
            })
            r.addView(TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            })
            body.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(7), 0, 0) })
        }

        if (c.custom) {
            row("类型", "课程/值班（手动添加）")
            if (c.room.isNotEmpty()) row("地点", c.room)
        } else {
            row("周次", c.weeks.ifEmpty { "每周" })
            if (c.room.isNotEmpty()) row("教室", c.room)
            if (c.teacher.isNotEmpty()) row("教师", c.teacher)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackground(roundedTop(headerColor, dp(20)))
        }
        header.addView(TextView(this).apply {
            text = c.name
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (subtitle.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor((0xFFFFFFFF.toInt() and 0x00FFFFFF) or 0xD9000000.toInt())
                setPadding(0, dp(5), 0, 0)
            })
        }
        root.addView(header)
        root.addView(body)

        val dlg = AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(if (c.custom) "删除" else "知道了", null)
            .show()
        if (c.custom) {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { deleteCustomAt(dayIdx, periodIdx); dlg.dismiss() }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
        }
        dlg.window?.setBackgroundDrawable(roundedColor(0xFFFFFFFF.toInt(), dp(20)))
    }

    /** 上圆角矩形（对话框头部用） */
    private fun roundedTop(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius.toFloat(), radius.toFloat(),
                radius.toFloat(), radius.toFloat(),
                0f, 0f, 0f, 0f,
            )
            setColor(color)
        }

    /** 删除覆盖了 (dayIdx, periodIdx) 这格的值班 */
    private fun deleteCustomAt(dayIdx: Int, periodIdx: Int) {
        val t = timetable ?: return
        val idx = t.custom.indexOfFirst { e ->
            e.day == dayIdx && periodIdx >= e.period - 1 && periodIdx < e.period - 1 + e.span
        }
        if (idx < 0) return
        timetable = t.copy(custom = t.custom.filterIndexed { i, _ -> i != idx })
        TimetableStore.save(this, timetable!!)
        render()
        toast("已删除")
    }

    /** 添加值班：选星期、节次（可连堂），填名称和地点；显示为深紫色卡片 */
    private fun showAddDutyDialog() {
        val t = timetable
        if (t == null) {
            toast("请先导入课表，再添加")
            return
        }
        val nPeriods = t.periods.size

        fun spinner(items: List<String>): android.widget.Spinner =
            android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items,
                )
            }

        // 值班按“大节”添加：1-2节、3-4节……（最后一节落单时单独成节）
        val blocks = buildList {
            var s = 1
            while (s <= nPeriods) {
                val e = minOf(s + 1, nPeriods)
                add(Triple(s, e, if (s == e) "第${s}节" else "第${s}-${e}节"))
                s += 2
            }
        }
        val daySp = spinner((0..6).map { "周" + "一二三四五六日"[it] })
        val blockSp = spinner(blocks.map { it.third })

        val nameEt = EditText(this).apply { hint = "名称，如：图书馆值班" }
        val roomEt = EditText(this).apply { hint = "值班地点，如：图书馆201（可空）" }

        val box = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun row(label: String, view: View) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(TextView(this).apply {
                text = label
                setPadding(0, 0, dp(12), 0)
            })
            r.addView(view, LinearLayout.LayoutParams(0, WRAP, 1f))
            form.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        }
        row("星期", daySp)
        row("大节", blockSp)
        form.addView(nameEt, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
        form.addView(roomEt, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(form)

        val dlg = AlertDialog.Builder(this)
            .setTitle("添加课程/值班")
            .setView(box)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEt.text.toString().trim()
            if (name.isEmpty()) {
                toast("请填写名称")
                return@setOnClickListener
            }
            val day = daySp.selectedItemPosition
            val block = blocks[blockSp.selectedItemPosition]
            val (s, e) = block.first to block.second
            val rangeText = if (s == e) "第${s}节" else "第${s}-${e}节"
            // 该大节里有课则不允许添加（无论单双周，以免覆盖原课）
            val conflict = (s..e).mapNotNull { p0 -> t.cells.getOrNull(p0 - 1)?.getOrNull(day) }.firstOrNull()
            if (conflict != null) {
                toast("$rangeText 已有课程「${conflict.name}」，不能在此添加")
                return@setOnClickListener
            }
            // 也不能与已添加的课程/值班重叠
            val overlapped = t.custom.any { it.day == day && s <= it.period + it.span - 1 && e >= it.period }
            if (overlapped) {
                toast("$rangeText 已添加过课程/值班")
                return@setOnClickListener
            }
            val entry = CustomEntry(
                day = day,
                period = s,
                span = e - s + 1,
                name = name,
                room = roomEt.text.toString().trim(),
            )
            timetable = t.copy(custom = t.custom + entry)
            TimetableStore.save(this, timetable!!)
            render()
            dlg.dismiss()
            toast("已添加，点击紫色卡片可删除")
        }
    }

    /** 管理已添加的课程/值班：单选删除 */
    private fun showDeleteDutyDialog() {
        val t = timetable ?: return
        if (t.custom.isEmpty()) {
            toast("还没有添加过课程/值班；点课程卡片也可直接删除")
            return
        }
        val labels = t.custom.map { e ->
            buildString {
                append("周").append("一二三四五六日"[e.day]).append(" 第${e.period}节")
                if (e.span > 1) append("-${e.period + e.span - 1}节")
                append(" ").append(e.name)
                if (e.room.isNotEmpty()) append("（").append(e.room).append("）")
            }
        }
        val selected = intArrayOf(-1)
        AlertDialog.Builder(this)
            .setTitle("删除课程/值班")
            .setSingleChoiceItems(labels.toTypedArray(), -1) { _, which -> selected[0] = which }
            .setPositiveButton("删除") { _, _ ->
                if (selected[0] >= 0) {
                    timetable = t.copy(custom = t.custom.filterIndexed { i, _ -> i != selected[0] })
                    TimetableStore.save(this, timetable!!)
                    render()
                    toast("已删除")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSetWeekStartDialog() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val sel = Calendar.getInstance().apply {
                clear()
                set(y, m, d, 0, 0, 0)
            }
            normalizeToMonday(sel)
            TimetableStore.saveSemesterStart(this, sel.timeInMillis)
            displayWeek = -1
            render()
            val fmt = SimpleDateFormat("M月d日", Locale.CHINA).format(sel.time)
            toast("第1周从 $fmt 开始，当前是第${currentWeek()}周")
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showPeriodTimesDialog() {
        val count = timetable?.periods?.size ?: Timetable.DEFAULT_PERIOD_TIMES.size
        val saved = TimetableStore.loadPeriodTimes(this)
        val container = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val starts = ArrayList<EditText>(count)
        val ends = ArrayList<EditText>(count)
        for (i in 0 until count) {
            val cur = saved.getOrNull(i) ?: Timetable.DEFAULT_PERIOD_TIMES.getOrNull(i) ?: ("--:--" to "--:--")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "第${i + 1}节"
                setPadding(0, 0, dp(12), 0)
            })
            row.addView(EditText(this).apply {
                hint = "开始"; setText(cur.first)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                starts.add(this)
            })
            row.addView(TextView(this).apply { text = " ~ "; setPadding(dp(6), 0, dp(6), 0) })
            row.addView(EditText(this).apply {
                hint = "结束"; setText(cur.second)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                ends.add(this)
            })
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        }
        container.addView(box)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.period_times))
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val times = (0 until count).map { starts[it].text.toString().trim() to ends[it].text.toString().trim() }
                TimetableStore.savePeriodTimes(this, times)
                timetable?.let { tt ->
                    val periods = tt.periods.mapIndexed { i, p -> p.copy(start = times[i].first, end = times[i].second) }
                    timetable = tt.copy(periods = periods)
                    TimetableStore.save(this, timetable!!)
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.help))
            .setMessage(
                """
                1. 点击“导入课表”选择 Excel 文件（.xlsx / .xls）。

                2. 支持两种 Excel 格式（可点菜单“保存模板”获得示例文件）：

                   ① 教务系统导出的列表格式（如“我的课表.xls”）：
                      表头包含：课程名、上课周次、上课星期、开始节次、结束节次、上课教师、教室名称
                      每行一门课，自动处理连堂和单双周（如“1-15周(单)”）。

                   ② 周视图网格格式：
                      · 第一行：节次、周一、周二……
                      · 左边一列：节次名称（如 第1节）
                      · 单元格：课程名@教室@教师（教室、教师可省略）

                3. 顶部“◀ 第x周 ▶”可切换查看不同周的课程（单双周会自动区分），点“第x周”回到本周。

                4. 学期第 1 周的日期默认为 2026年9月7日，可在菜单“设置第1周”里修改，当前周数会自动推算。

                5. 可在“节次时间”里设置每节课的上下课时间（显示在课程详情里），课表自动保存。
                """.trimIndent()
            )
            .setPositiveButton("好的", null)
            .show()
    }

    // ---------- 工具 ----------

    private fun todaySubtitle(): String {
        val c = Calendar.getInstance()
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(c.time)
        val dayIdx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一=0
        val week = if (dayIdx in weekChars.indices) "周${weekChars[dayIdx]}" else ""
        return getString(R.string.today_fmt, week, date)
    }

    private val weekChars = listOf("一", "二", "三", "四", "五", "六", "日")

    private fun roundedColor(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }

    private fun queryName(uri: android.net.Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        /** 今天列：空格底色（深蓝） */
        private val COLOR_TODAY_EMPTY = 0xFF2E4A6B.toInt()
        /** 手动添加的值班卡片底色（深紫） */
        private val COLOR_CUSTOM = 0xFF4527A0.toInt()
        private val FILE_EXCEL = arrayOf(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msexcel",
            "application/x-xls",
        )
    }
}
