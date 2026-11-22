package com.renpytools.app

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * Dekompiler RPYC best-effort. Membaca pickle AST Ren'Py TANPA menjalankannya
 * dan menulis ulang node yang umum menjadi sintaks .rpy.
 */
object RpycDecompiler {

    fun decompile(bytes: ByteArray): String {
        val payload = extractPayload(bytes)
        val root = PickleReader(payload).load()
        val stmts = findStmts(root)

        val sb = StringBuilder()
        sb.append("# Dekompilasi best-effort oleh RenPy Tools.\n")
        sb.append("# Periksa hasilnya; node yang belum didukung ditandai sebagai komentar.\n\n")
        emitBlock(stmts, 0, sb)
        return sb.toString()
    }

    // ------------------------------------------------------------ format file

    private fun le32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) or
            ((b[p + 1].toLong() and 0xff) shl 8) or
            ((b[p + 2].toLong() and 0xff) shl 16) or
            ((b[p + 3].toLong() and 0xff) shl 24))

    private fun inflate(b: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(b)).readBytes()

    private fun extractPayload(bytes: ByteArray): ByteArray {
        val magic = "RENPY RPC2".toByteArray(Charsets.ISO_8859_1)
        if (bytes.size > magic.size && bytes.copyOf(magic.size).contentEquals(magic)) {
            var p = magic.size
            while (p + 12 <= bytes.size) {
                val slot = le32(bytes, p)
                val start = le32(bytes, p + 4)
                val len = le32(bytes, p + 8)
                p += 12
                if (slot == 0L) break
                if (slot == 1L) {
                    require(start + len <= bytes.size) { "File RPYC terpotong" }
                    return inflate(bytes.copyOfRange(start.toInt(), (start + len).toInt()))
                }
            }
            throw IllegalArgumentException("Slot data tidak ditemukan di RPYC2")
        }
        // RPYC versi 1: seluruh file adalah pickle yang dikompres zlib.
        return try {
            inflate(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException("Bukan file .rpyc yang valid")
        }
    }

    private fun findStmts(root: Any?): List<Any?> {
        if (root is List<*>) {
            if (root.size == 2 && root[1] is List<*>) return root[1] as List<*>
            return root
        }
        return emptyList()
    }

    // ------------------------------------------------------------ helper

    private fun text(x: Any?): String? = when (x) {
        null -> null
        is String -> x
        is Number, is Boolean -> x.toString()
        is PyObj -> x.args?.firstOrNull()?.let { text(it) }
        else -> null
    }

    private fun asList(x: Any?): List<Any?> = when (x) {
        is List<*> -> x
        is PyObj -> x.items
        else -> emptyList()
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun StringBuilder.line(s: Any) {
        append(s).append('\n')
    }

    private fun pySource(code: Any?): String? {
        if (code is String) return code
        if (code is PyObj) {
            val st = code.state
            if (st is List<*> && st.size >= 2 && st[1] != null) return text(st[1])
            text(code.attr("source"))?.let { return it }
            val a = code.args
            if (a != null && a.isNotEmpty()) return text(a[0])
        }
        return null
    }

    // ------------------------------------------------------------ emitter

    private fun emitBlock(nodes: List<Any?>, d: Int, sb: StringBuilder) {
        val start = sb.length
        for (n in nodes) emitNode(n, d, sb)
        if (sb.length == start) sb.line("    ".repeat(d) + "pass")
    }

    private fun emitPythonBlock(header: String, src: String, ind: String, sb: StringBuilder) {
        sb.line(ind + header)
        val lines = src.trimEnd().lines()
        for (l in lines) sb.line(if (l.isBlank()) "" else "$ind    $l")
    }

    private fun emitNode(n: Any?, d: Int, sb: StringBuilder) {
        val o = n as? PyObj ?: return
        val ind = "    ".repeat(d)

        when (o.shortName) {
            "Label" -> {
                if (d == 0) sb.append('\n')
                sb.line("${ind}label ${text(o.attr("name")) ?: "?"}:")
                emitBlock(asList(o.attr("block")), d + 1, sb)
            }

            "Say" -> {
                val who = text(o.attr("who"))
                val what = text(o.attr("what")) ?: ""
                val attrs = asList(o.attr("attributes")).mapNotNull { text(it) }
                val l = StringBuilder(ind)
                if (who != null) l.append(who).append(' ')
                if (attrs.isNotEmpty()) l.append(attrs.joinToString(" ")).append(' ')
                l.append(quote(what))
                if (o.attr("interact") == false) l.append(" nointeract")
                sb.line(l)
            }

            "Menu" -> {
                sb.line("${ind}menu:")
                val ind2 = "$ind    "
                for (it in asList(o.attr("items"))) {
                    val t = it as? List<*> ?: continue
                    val label = text(t.getOrNull(0)) ?: ""
                    val cond = text(t.getOrNull(1))
                    val blk = t.getOrNull(2)
                    val head = StringBuilder(ind2).append(quote(label))
                    if (blk == null) {
                        sb.line(head) // baris caption
                    } else {
                        if (cond != null && cond != "True") head.append(" if ").append(cond)
                        head.append(':')
                        sb.line(head)
                        emitBlock(asList(blk), d + 2, sb)
                    }
                }
            }

            "If" -> {
                asList(o.attr("entries")).forEachIndexed { i, e ->
                    val t = e as? List<*> ?: return@forEachIndexed
                    val cond = text(t.getOrNull(0)) ?: "True"
                    val head = when {
                        i == 0 -> "if $cond:"
                        cond == "True" -> "else:"
                        else -> "elif $cond:"
                    }
                    sb.line(ind + head)
                    emitBlock(asList(t.getOrNull(1)), d + 1, sb)
                }
            }

            "While" -> {
                sb.line("${ind}while ${text(o.attr("condition")) ?: "True"}:")
                emitBlock(asList(o.attr("block")), d + 1, sb)
            }

            "Jump" -> {
                val target = text(o.attr("target")) ?: "?"
                sb.line(if (o.attr("expression") == true) "${ind}jump expression $target" else "${ind}jump $target")
            }

            "Call" -> {
                val label = text(o.attr("label")) ?: "?"
                sb.line(if (o.attr("expression") == true) "${ind}call expression $label" else "${ind}call $label")
            }

            "Return" -> {
                val e = text(o.attr("expression"))
                sb.line(if (e == null) "${ind}return" else "${ind}return $e")
            }

            "Pass" -> Unit

            "Python", "EarlyPython" -> {
                val src = pySource(o.attr("code")) ?: ""
                if (!src.trim().contains('\n')) {
                    sb.line(ind + "\$ " + src.trim())
                } else {
                    val hide = o.attr("hide") == true
                    emitPythonBlock(if (hide) "python hide:" else "python:", src, ind, sb)
                }
            }

            "Init" -> {
                val prio = (o.attr("priority") as? Number)?.toInt() ?: 0
                val blk = asList(o.attr("block"))
                val single = blk.singleOrNull() as? PyObj
                when (single?.shortName) {
                    "Define", "Default", "Image", "Transform", "Screen", "Style", "Translate" ->
                        emitNode(single, d, sb)
                    "Python", "EarlyPython" -> {
                        val early = single!!.shortName == "EarlyPython"
                        val src = pySource(single.attr("code")) ?: ""
                        val pr = if (prio != 0) " $prio" else ""
                        emitPythonBlock("init$pr python${if (early) " early" else ""}:", src, ind, sb)
                    }
                    else -> {
                        sb.line(if (prio != 0) "${ind}init $prio:" else "${ind}init:")
                        emitBlock(blk, d + 1, sb)
                    }
                }
            }

            "Define", "Default" -> {
                val kw = o.shortName.lowercase()
                val v = text(o.attr("varname")) ?: "?"
                val store = text(o.attr("store"))
                val ns = store?.removePrefix("store")?.removePrefix(".")
                val name = if (ns.isNullOrEmpty()) v else "$ns.$v"
                val op = if (kw == "define") (text(o.attr("operator")) ?: "=") else "="
                sb.line("$ind$kw $name $op ${pySource(o.attr("code")) ?: "None"}")
            }

            "Scene", "Show", "Hide" -> {
                val kw = o.shortName.lowercase()
                val imspec = o.attr("imspec") as? List<*>
                val l = StringBuilder(ind).append(kw)
                if (imspec != null) {
                    val name = asList(imspec.getOrNull(0)).mapNotNull { text(it) }.joinToString(" ")
                    val expr = text(imspec.getOrNull(1))
                    if (expr != null) l.append(" expression ").append(expr)
                    else if (name.isNotEmpty()) l.append(' ').append(name)
                    val at = asList(imspec.getOrNull(3)).mapNotNull { text(it) }
                    if (at.isNotEmpty()) l.append(" at ").append(at.joinToString(", "))
                }
                sb.line(l)
            }

            "With" -> sb.line("${ind}with ${text(o.attr("expr")) ?: "None"}")

            "UserStatement" -> {
                val l = text(o.attr("line")) ?: ""
                val blk = asList(o.attr("block"))
                if (blk.isEmpty()) {
                    sb.line(ind + l)
                } else {
                    sb.line("$ind$l:")
                    emitBlock(blk, d + 1, sb)
                }
            }

            else -> sb.line("$ind# [belum didukung: ${o.cls}]")
        }
    }
}
