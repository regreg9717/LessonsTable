package com.lightcourse.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成一份符合导入格式的示例课表 .xlsx（教务系统列表格式，与“我的课表.xls”相同布局），
 * 保存到应用外部目录并弹出分享。
 */
object TemplateExporter {

    private val HEADERS = listOf(
        "课程号", "课程名", "课序号", "开课单位", "学分", "上课周次", "上课星期",
        "开始节次", "结束节次", "上课教师", "教室名称", "课程性质", "课程类别", "校公选课类别",
    )

    // 每行一门课的排课记录（与教务导出一致；前 6 列与后 3 列可留空）
    private val SAMPLE_ROWS = listOf(
        listOf("", "高等数学", "", "", "3", "1-15周", "星期一", "1", "2", "王老师", "A栋301", "", "", ""),
        listOf("", "大学英语", "", "", "2", "1-15周(双)", "星期一", "3", "4", "李老师", "B栋201", "", "", ""),
        listOf("", "数据结构", "", "", "3", "1-15周", "星期二", "3", "4", "张老师", "C栋105", "", "", ""),
        listOf("", "操作系统", "", "", "3", "1-15周", "星期三", "5", "6", "陈老师", "C栋202", "", "", ""),
        listOf("", "线性代数", "", "", "3", "1-15周(单)", "星期四", "1", "2", "赵老师", "B栋110", "", "", ""),
        listOf("", "计算机网络", "", "", "3", "1-15周", "星期五", "5", "6", "孙老师", "D栋303", "", "", ""),
        listOf("", "体育", "", "", "1", "1-15周", "星期五", "7", "8", "刘老师", "操场", "", "", ""),
    )

    fun export(context: Context): File {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "Documents")
            .apply { mkdirs() }
        val out = File(dir, "课表模板.xlsx")
        out.outputStream().use { writeXlsx(it) }
        return out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送模板文件"))
    }

    private fun writeXlsx(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")

            put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")

            put(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="课表" sheetId="1" r:id="rId1"/></sheets>
</workbook>""")

            put(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")

            put(zip, "xl/worksheets/sheet1.xml", sheetXml())
        }
    }

    private fun sheetXml(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sb.append("<row r=\"1\">")
        HEADERS.forEachIndexed { i, h -> cell(sb, i, h) }
        sb.append("</row>")
        SAMPLE_ROWS.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 2}\">")
            row.forEachIndexed { c, v -> if (v.isNotEmpty()) cell(sb, c, v) }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cell(sb: StringBuilder, col: Int, value: String) {
        sb.append("<c r=\"").append(colRef(col)).append("\" t=\"inlineStr\"><is><t>")
            .append(escape(value)).append("</t></is></c>")
    }

    private fun colRef(zeroBased: Int): String {
        var n = zeroBased
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}
