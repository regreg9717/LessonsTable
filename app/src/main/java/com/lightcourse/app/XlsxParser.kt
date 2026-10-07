package com.lightcourse.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 手写解析 .xlsx（OpenXML 本质是 zip + xml），不引入 poi-ooxml。
 * 返回二维表格 grid[row][col]，全部转为字符串（空单元格为 null）。
 */
object XlsxParser {

    fun parse(input: InputStream): List<List<String?>> {
        // ZipInputStream 只能顺序读，先全部缓存进内存（课表文件很小）
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
                z.closeEntry()
            }
        }

        val shared = entries["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()

        // workbook.xml -> 第一个 sheet 的 r:id
        val sheetId = entries["xl/workbook.xml"]?.let { firstSheetRelId(it) } ?: "rId1"
        // workbook.xml.rels -> rId 对应的 target 路径
        val target = entries["xl/_rels/workbook.xml.rels"]?.let { relTarget(it, sheetId) }
            ?: "worksheets/sheet1.xml"
        val sheetPath = normalize("xl/", target)
        val sheetBytes = entries[sheetPath]
            ?: entries.entries.firstOrNull { it.key.startsWith("xl/worksheets/") }?.value
            ?: throw IllegalArgumentException("xlsx 中找不到工作表")

        return readSheet(sheetBytes, shared)
    }

    private fun normalize(base: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        if (target.startsWith("xl/")) return target
        return base + target
    }

    private fun firstSheetRelId(bytes: ByteArray): String {
        val p = newParser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                for (i in 0 until p.attributeCount) {
                    if (p.getAttributeName(i) == "id") return p.getAttributeValue(i)
                }
            }
        }
        return "rId1"
    }

    private fun relTarget(bytes: ByteArray, relId: String): String {
        val p = newParser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                var id: String? = null
                var tgt: String? = null
                for (i in 0 until p.attributeCount) {
                    when (p.getAttributeName(i)) {
                        "Id" -> id = p.getAttributeValue(i)
                        "Target" -> tgt = p.getAttributeValue(i)
                    }
                }
                if (id == relId && tgt != null) return tgt
            }
        }
        return "worksheets/sheet1.xml"
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inSi = false
        val p = newParser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> if (inSi) sb.append(p.nextText())
                }
                XmlPullParser.END_TAG -> if (p.name == "si") {
                    inSi = false
                    out.add(sb.toString())
                }
            }
        }
        return out
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String?>> {
        val grid = mutableListOf<MutableList<String?>>()
        var currentRow: MutableList<String?>? = null
        val p = newParser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "row" -> {
                    // 按 r 属性（1-based 行号）对齐，跳行时补空行
                    val rowNo = attr(p, "r")?.toIntOrNull() ?: (grid.size + 1)
                    while (grid.size < rowNo - 1) grid.add(mutableListOf())
                    currentRow = mutableListOf()
                    grid.add(currentRow)
                }
                "c" -> {
                    val row = currentRow ?: continue
                    val ref = attr(p, "r")            // 例如 "C12"
                    val type = attr(p, "t")
                    val col = colIndex(ref, row.size)
                    while (row.size < col) row.add(null)
                    val text: String? = when (type) {
                        "s" -> {
                            val v = readV(p)
                            v?.toIntOrNull()?.let { shared.getOrNull(it) }
                        }
                        "inlineStr" -> {
                            // <is><t>...</t></is>
                            var t: String? = null
                            while (p.next() != XmlPullParser.END_TAG || p.name != "c") {
                                if (p.eventType == XmlPullParser.START_TAG && p.name == "t") {
                                    t = p.nextText()
                                }
                            }
                            t
                        }
                        else -> readV(p)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    while (row.size <= col) row.add(null)
                    row[col] = text?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        return grid
    }

    /** 读 <v>xxx</v>（单元格值） */
    private fun readV(p: XmlPullParser): String? {
        while (p.next() != XmlPullParser.END_TAG) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "v") {
                return p.nextText()
            }
        }
        return null
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == name) return p.getAttributeValue(i)
        return null
    }

    /** "C12" -> 2；无 ref 时按顺序摆放 */
    private fun colIndex(ref: String?, fallback: Int): Int {
        if (ref.isNullOrEmpty()) return fallback
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1)
            else break
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        return p
    }
}
