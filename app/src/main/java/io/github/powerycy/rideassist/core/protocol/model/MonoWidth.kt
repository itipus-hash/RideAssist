package io.github.powerycy.rideassist.core.protocol.model

/**
 * ⭐ 等宽字体下的**显示宽度**与列对齐工具。
 *
 * ## 为什么不能用 `String.length`
 * 中日韩文字在等宽字体里占 **2 个字符位**，而 `String.length` 记 1。
 * 用 `length` 算出来的对齐宽度会偏小，导致所有含中文的行错位。
 *
 * ## 哪些字符算 2 格
 * 不能简单用 `code > 0x2E80` 判断 —— 那是「中日韩统一表意文字」区段起点，
 * 但**符号类**里也有全宽字符，且有些在它之前：
 * - `℃` U+2103（摄氏度）← 本项目就踩了这个
 * - `°` U+00B0（度符号）通常算 1，但配合 `℃` 会一起造成视觉错位
 * - 全角空格 `　` U+3000
 * - 箭头 `→` U+2192、区块图形 `─` 等制表符号
 *
 * 判断规则：落在 East Asian Width = Wide / Fullwidth 的区间就算 2。
 * 这里用区间表覆盖常见情况，不追求 Unicode 全表完备。
 */
internal object MonoWidth {

    /** 该码位是否占 2 个字符位 */
    fun charWidth(code: Int): Int = when {
        // 全角 ASCII / 全角标点（U+FF01..U+FF60、U+FFE0..U+FFE6）
        code in 0xFF01..0xFF60 -> 2
code in 0xFFE0..0xFFE6 -> 2
        // 中文、日文、韩文、假名、CJK 符号标点（含全角空格 U+3000）
     code in 0x1100..0x115F -> 2 // 韩文字母
        code in 0x2E80..0x303E -> 2 // CJK 部首 + 符号标点（含全角空格）
     code in 0x3041..0x33FF -> 2 // 平假名/片假名/注音/兼容符号
        code in 0x3400..0x4DBF -> 2 // 扩展 A
        code in 0x4E00..0x9FFF -> 2 // 基本汉字
        code in 0xA000..0xA4CF -> 2 // 彝文
        code in 0xAC00..0xD7A3 -> 2 // 韩文音节
     code in 0xF900..0xFAFF -> 2 // CJK 兼容汉字
        code in 0xFE10..0xFE19 -> 2 // 竖排标点
        code in 0xFE30..0xFE6F -> 2 // CJK 兼容形式
        code in 0xFF00..0xFF00 -> 2 // 全角空格替代
        // ⚠️ 以下是本项目实际踩到的符号
     code in 0x2100..0x214F -> 2 // ℃(U+2103) 等技术符号，全角
        code in 0x2190..0x21FF -> 2 // 箭头
        code in 0x2500..0x257F -> 2 // 制表/框线
        else -> 1
    }

    /** 字符串的显示宽度（中文算 2） */
    fun of(s: String): Int = s.sumOf { charWidth(it.code) }

    /** 右对齐补空格到指定显示宽度（用于数值列） */
    fun padLeft(s: String, target: Int): String {
        val gap = target - of(s)
        return if (gap <= 0) s else " ".repeat(gap) + s
    }

    /** 左对齐补空格到指定显示宽度（用于标签列） */
    fun padRight(s: String, target: Int): String {
     val gap = target - of(s)
        return if (gap <= 0) s else s + " ".repeat(gap)
    }

/**
     * 把一个数值单元格拆成「整数位+符号」与「小数位+单位」两段。
     *
     * ```
     * "3.655V" -> "3"    + ".655V"
     * "-20.0℃" -> "-20"  + ".0℃"
     * "4200mV" -> "4200" + "mV"      // 无小数点，整数位顶到轴上
     * "888A"   -> "888"  + "A"
     * ```
     */
    private fun splitNumeric(s: String): Pair<String, String> {
  if (s.isEmpty()) return "" to ""
        var i = 0
        if (s[0] == '-' || s[0] == '+') i++
        while (i < s.length && s[i].isDigit()) i++
     val head = s.substring(0, i)
     if (i < s.length && s[i] == '.') {
         // 小数点连同其后的位数 + 单位，全部归入尾段
return head to s.substring(i)
        }
      return head to s.substring(i)
    }
}
