package com.renpytools.app

import java.math.BigInteger

/** Referensi ke class/fungsi Python (opcode GLOBAL). Tidak pernah dieksekusi. */
class GlobalRef(val name: String)

/** Objek Python generik hasil unpickle. Tidak ada kode yang dijalankan. */
class PyObj(val cls: String) {
    var args: List<Any?>? = null
    var state: Any? = null
    val items = ArrayList<Any?>()
    val map = LinkedHashMap<Any?, Any?>()

    val shortName: String get() = cls.substringAfterLast('.')

    fun attr(name: String): Any? {
        val st = state
        if (st is Map<*, *> && st.containsKey(name)) return st[name]
        if (st is List<*>) {
            for (e in st) if (e is Map<*, *> && e.containsKey(name)) return e[name]
        }
        return map[name]
    }
}

/**
 * Pembaca pickle minimal (protokol 0-5) yang AMAN: hanya membangun struktur data,
 * tidak pernah memanggil callable apa pun dari file. Cukup untuk indeks RPA dan RPYC.
 */
class PickleReader(private val d: ByteArray) {
    private var p = 0
    private val stack = ArrayList<Any?>()
    private val marks = ArrayList<Int>()
    private val memo = HashMap<Int, Any?>()

    private fun u8(): Int = d[p++].toInt() and 0xff
    private fun u16(): Int = u8() or (u8() shl 8)
    private fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
    private fun i64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
        return v
    }

    private fun take(n: Int): ByteArray {
        require(n >= 0 && p + n <= d.size) { "Pickle terpotong" }
        val r = d.copyOfRange(p, p + n)
        p += n
        return r
    }

    private fun line(): String {
        val s = p
        while (d[p] != '\n'.code.toByte()) p++
        val r = String(d, s, p - s, Charsets.ISO_8859_1)
        p++
        return r
    }

    private fun pop(): Any? = stack.removeAt(stack.size - 1)
    private fun push(v: Any?) {
        stack.add(v)
    }

    private fun popMark(): List<Any?> {
        val m = marks.removeAt(marks.size - 1)
        val r = ArrayList(stack.subList(m, stack.size))
        while (stack.size > m) stack.removeAt(stack.size - 1)
        return r
    }

    private fun longFromBytes(n: Int): Long {
        if (n == 0) return 0L
        val b = take(n)
        return BigInteger(b.reversedArray()).toLong()
    }

    private fun utf8(n: Int): String = String(take(n), Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun addTo(t: Any?, v: Any?) {
        when (t) {
            is PyObj -> t.items.add(v)
            is MutableList<*> -> (t as MutableList<Any?>).add(v)
            is MutableSet<*> -> (t as MutableSet<Any?>).add(v)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun putTo(t: Any?, k: Any?, v: Any?) {
        when (t) {
            is PyObj -> t.map[k] = v
            is MutableMap<*, *> -> (t as MutableMap<Any?, Any?>)[k] = v
        }
    }

    private fun reduce(f: Any?, args: Any?): Any? {
        val a: List<Any?> = (args as? List<*>)?.toList() ?: emptyList()
        if (f is GlobalRef) {
            when (f.name) {
                "_codecs.encode" ->
                    return ((a.getOrNull(0) as? String) ?: "").toByteArray(Charsets.ISO_8859_1)
                "__builtin__.set", "builtins.set", "__builtin__.frozenset", "builtins.frozenset" ->
                    return LinkedHashSet<Any?>((a.getOrNull(0) as? Collection<*>) ?: emptyList<Any?>())
                "collections.OrderedDict" -> {
                    val m = LinkedHashMap<Any?, Any?>()
                    (a.getOrNull(0) as? List<*>)?.forEach { pair ->
                        if (pair is List<*> && pair.size == 2) m[pair[0]] = pair[1]
                    }
                    return m
                }
                "copy_reg._reconstructor", "copyreg._reconstructor" -> {
                    val c = a.getOrNull(0)
                    if (c is GlobalRef) return PyObj(c.name).also { it.args = a }
                }
                "copy_reg.__newobj__", "copyreg.__newobj__" -> {
                    val c = a.getOrNull(0)
                    if (c is GlobalRef) return PyObj(c.name)
                }
            }
            return PyObj(f.name).also { it.args = a }
        }
        return PyObj("?").also { it.args = a }
    }

    fun load(): Any? {
        while (true) {
            val op = u8()
            when (op) {
                0x80 -> p++ // PROTO
                0x95 -> p += 8 // FRAME
                '.'.code -> return pop()
                '('.code -> marks.add(stack.size)
                '0'.code -> pop()
                '1'.code -> popMark()
                '2'.code -> push(stack[stack.size - 1])
                'N'.code -> push(null)
                0x88 -> push(true)
                0x89 -> push(false)
                'I'.code -> {
                    val s = line()
                    push(
                        when (s) {
                            "00" -> false
                            "01" -> true
                            else -> s.toLong()
                        }
                    )
                }
                'J'.code -> push(i32().toLong())
                'K'.code -> push(u8().toLong())
                'M'.code -> push(u16().toLong())
                'L'.code -> push(BigInteger(line().removeSuffix("L")).toLong())
                0x8a -> push(longFromBytes(u8()))
                0x8b -> push(longFromBytes(i32()))
                'F'.code -> push(line().toDouble())
                'G'.code -> {
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or u8().toLong()
                    push(java.lang.Double.longBitsToDouble(v))
                }
                'S'.code -> push(line().trim().trim('\'', '"'))
                'T'.code -> push(utf8(i32()))
                'U'.code -> push(utf8(u8()))
                'V'.code -> push(line())
                'X'.code -> push(utf8(i32()))
                0x8c -> push(utf8(u8()))
                0x8d -> push(utf8(i64().toInt()))
                'B'.code -> push(take(i32()))
                'C'.code -> push(take(u8()))
                0x8e, 0x96 -> push(take(i64().toInt()))
                ')'.code -> push(ArrayList<Any?>())
                0x85 -> tupleN(1)
                0x86 -> tupleN(2)
                0x87 -> tupleN(3)
                't'.code -> push(ArrayList(popMark()))
                ']'.code -> push(ArrayList<Any?>())
                'l'.code -> push(ArrayList(popMark()))
                '}'.code -> push(LinkedHashMap<Any?, Any?>())
                'd'.code -> {
                    val items = popMark()
                    val m = LinkedHashMap<Any?, Any?>()
                    var i = 0
                    while (i + 1 < items.size) {
                        m[items[i]] = items[i + 1]
                        i += 2
                    }
                    push(m)
                }
                0x8f -> push(LinkedHashSet<Any?>())
                0x90 -> {
                    val items = popMark()
                    val t = stack[stack.size - 1]
                    items.forEach { addTo(t, it) }
                }
                0x91 -> push(LinkedHashSet<Any?>(popMark()))
                'a'.code -> {
                    val v = pop()
                    addTo(stack[stack.size - 1], v)
                }
                'e'.code -> {
                    val items = popMark()
                    val t = stack[stack.size - 1]
                    items.forEach { addTo(t, it) }
                }
                's'.code -> {
                    val v = pop()
                    val k = pop()
                    putTo(stack[stack.size - 1], k, v)
                }
                'u'.code -> {
                    val items = popMark()
                    val t = stack[stack.size - 1]
                    var i = 0
                    while (i + 1 < items.size) {
                        putTo(t, items[i], items[i + 1])
                        i += 2
                    }
                }
                'c'.code -> {
                    val m = line()
                    val n = line()
                    push(GlobalRef("$m.$n"))
                }
                0x93 -> {
                    val n = pop() as String
                    val m = pop() as String
                    push(GlobalRef("$m.$n"))
                }
                'R'.code -> {
                    val args = pop()
                    val f = pop()
                    push(reduce(f, args))
                }
                0x81 -> {
                    val args = pop()
                    val cls = pop()
                    push(
                        if (cls is GlobalRef) PyObj(cls.name).also { it.args = args as? List<Any?> }
                        else PyObj("?")
                    )
                }
                0x92 -> {
                    pop() // kwargs
                    val args = pop()
                    val cls = pop()
                    push(
                        if (cls is GlobalRef) PyObj(cls.name).also { it.args = args as? List<Any?> }
                        else PyObj("?")
                    )
                }
                'o'.code -> {
                    val items = popMark()
                    val c = items.firstOrNull()
                    push(if (c is GlobalRef) PyObj(c.name) else PyObj("?"))
                }
                'i'.code -> {
                    val m = line()
                    val n = line()
                    popMark()
                    push(PyObj("$m.$n"))
                }
                'b'.code -> {
                    val st = pop()
                    val o = stack[stack.size - 1]
                    if (o is PyObj) o.state = st
                }
                'p'.code -> memo[line().trim().toInt()] = stack[stack.size - 1]
                'q'.code -> memo[u8()] = stack[stack.size - 1]
                'r'.code -> memo[i32()] = stack[stack.size - 1]
                0x94 -> memo[memo.size] = stack[stack.size - 1]
                'g'.code -> push(memo[line().trim().toInt()])
                'h'.code -> push(memo[u8()])
                'j'.code -> push(memo[i32()])
                'Q'.code -> {
                    pop()
                    push(null)
                }
                else -> throw IllegalStateException(
                    "Opcode pickle 0x%02x tidak didukung (posisi %d)".format(op, p - 1)
                )
            }
        }
    }

    private fun tupleN(n: Int) {
        val items = ArrayList<Any?>(stack.subList(stack.size - n, stack.size))
        repeat(n) { stack.removeAt(stack.size - 1) }
        push(items)
    }
}
