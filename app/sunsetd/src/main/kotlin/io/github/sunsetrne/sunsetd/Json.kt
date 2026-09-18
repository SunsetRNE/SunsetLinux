package io.github.sunsetrne.sunsetd

/**
 * 极小的 JSON 工具（**故意不引第三方依赖**）。
 *
 * 为什么手写：内核要跑在 `app_process` 上、随模块分发一个几十 KB 的 dex，
 * 引一个 JSON 库就是把"零依赖"这条好处扔掉。而内核需要的 JSON 面很窄：
 *   · 写出：状态文件、`status` 响应、控制帧（都是我们自己定义的形状）；
 *   · 读入：自己的状态文件（**平铺对象**）+ 控制帧（平铺 + 少量嵌套）。
 *
 * ⚠️ 能力边界（写清楚，免得以后有人当通用库用）：
 *   · [parseFlatObject] 只解析**一层**对象；遇到嵌套对象/数组时把它的**原始文本**
 *     作为值返回（不递归）—— 我们的状态文件是平铺的，控制帧里的嵌套由调用方自己再解一次。
 *   · 不处理 `\uXXXX` 之外的花式转义（我们自己写出去的只有 `\"` `\\` `\n` `\r` `\t`）。
 */
object Json {

    /** 渲染一个对象；`null` 值渲染成 JSON `null`（而不是省略键 —— 键必须稳定）。 */
    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"${escape(k)}\":${value(v)}"
        }

    fun arr(items: List<Any?>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { value(it) }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${escape(v)}\""
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Raw -> v.text
        is List<*> -> arr(v)
        else -> "\"${escape(v.toString())}\""
    }

    /** 已经是 JSON 片段、不要再加引号的内容。 */
    class Raw(val text: String)

    /**
     * 解析**一层**对象 → `Map<String, String>`（值统一转成字符串；`null` 变成 `"null"`）。
     * 解析失败返回 null（调用方退化为默认状态，绝不抛给设备）。
     */
    fun parseFlatObject(text: String): Map<String, String>? {
        val s = text.trim()
        if (!s.startsWith("{")) return null
        val out = LinkedHashMap<String, String>()
        var i = 1
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        while (true) {
            ws()
            if (i >= s.length) return null
            if (s[i] == '}') return out
            if (s[i] != '"') return null
            val key = readString(s, i) ?: return null
            i = key.second
            ws()
            if (i >= s.length || s[i] != ':') return null
            i++
            ws()
            val v = readValue(s, i) ?: return null
            out[key.first] = v.first
            i = v.second
            ws()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == '}') return out
            return null
        }
    }

    /** 读一个值：字符串解开转义，其它（数字/布尔/null/嵌套）原样取文本。 */
    private fun readValue(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length) return null
        return when (s[start]) {
            '"' -> readString(s, start)
            '{', '[' -> {
                var depth = 0
                var i = start
                var inStr = false
                while (i < s.length) {
                    val c = s[i]
                    when {
                        inStr && c == '\\' -> i++
                        c == '"' -> inStr = !inStr
                        !inStr && (c == '{' || c == '[') -> depth++
                        !inStr && (c == '}' || c == ']') -> {
                            depth--
                            if (depth == 0) return s.substring(start, i + 1) to (i + 1)
                        }
                    }
                    i++
                }
                null
            }
            else -> {
                var i = start
                while (i < s.length && s[i] != ',' && s[i] != '}' && !s[i].isWhitespace()) i++
                if (i == start) null else s.substring(start, i) to i
            }
        }
    }

    private fun readString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    i++
                    if (i >= s.length) return null
                    when (val e = s[i]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            if (i + 4 >= s.length) return null
                            val hex = s.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                            sb.append(hex.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }
}
