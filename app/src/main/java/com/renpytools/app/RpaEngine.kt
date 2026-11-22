package com.renpytools.app

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class RpaEntry(val name: String, val offset: Long, val length: Long, val prefix: ByteArray)
class RpaIndex(val version: String, val entries: List<RpaEntry>)

/** Menulis pickle protokol 2 untuk indeks RPA: {nama: [(offset^key, length^key)]}. */
object PickleWriter {
    fun index(entries: Map<String, LongArray>, key: Long): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x80); o.write(2)          // PROTO 2
        o.write('}'.code)                  // EMPTY_DICT
        o.write('('.code)                  // MARK
        for ((name, v) in entries) {
            str(o, name)
            o.write(']'.code)              // EMPTY_LIST
            int(o, v[0] xor key)
            int(o, v[1] xor key)
            o.write(0x86)                  // TUPLE2
            o.write('a'.code)              // APPEND
        }
        o.write('u'.code)                  // SETITEMS
        o.write('.'.code)                  // STOP
        return o.toByteArray()
    }

    private fun str(o: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write('X'.code)
        for (i in 0 until 4) o.write((b.size ushr (8 * i)) and 0xff)
        o.write(b)
    }

    private fun int(o: ByteArrayOutputStream, v: Long) {
        if (v in 0..255) {
            o.write('K'.code); o.write(v.toInt()); return
        }
        var n = 1
        while (n < 8 && (v ushr (8 * n)) != 0L) n++
        val bytes = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
        val pad = (bytes[n - 1].toInt() and 0x80) != 0
        o.write(0x8a)                      // LONG1
        o.write(if (pad) n + 1 else n)
        o.write(bytes)
        if (pad) o.write(0)
    }
}

object RpaEngine {
    private const val KEY = 0xDEADBEEFL
    private const val HEADER_LEN = 34 // "RPA-3.0 " + 16 hex + " " + 8 hex + "\n"

    // ---------------------------------------------------------------- baca

    fun readIndex(ch: FileChannel): RpaIndex {
        val head = ByteBuffer.allocate(256)
        ch.read(head, 0)
        val raw = head.array()
        var nl = -1
        for (i in 0 until head.position()) if (raw[i] == '\n'.code.toByte()) { nl = i; break }
        require(nl > 0) { "Header tidak valid: ini bukan file .rpa Ren'Py" }

        val parts = String(raw, 0, nl, Charsets.ISO_8859_1).trim().split(' ').filter { it.isNotEmpty() }
        val version = parts[0]
        val offset: Long
        var key = 0L
        when {
            version.startsWith("RPA-3.2") -> {
                offset = parts[1].toLong(16)
                for (i in 3 until parts.size) key = key xor parts[i].toLong(16)
            }
            version.startsWith("RPA-3.0") -> {
                offset = parts[1].toLong(16)
                for (i in 2 until parts.size) key = key xor parts[i].toLong(16)
            }
            version.startsWith("RPA-2.0") -> offset = parts[1].toLong(16)
            else -> throw IllegalArgumentException("Versi arsip belum didukung: $version")
        }

        val fileSize = ch.size()
        val size = fileSize - offset
        require(offset in 1 until fileSize && size < 256L * 1024 * 1024) { "Offset indeks tidak valid / file rusak" }

        val buf = ByteBuffer.allocate(size.toInt())
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        val inflated = InflaterInputStream(ByteArrayInputStream(buf.array(), 0, buf.position())).readBytes()
        val root = PickleReader(inflated).load() as? Map<*, *>
            ?: throw IllegalArgumentException("Indeks RPA tidak terbaca")

        val list = ArrayList<RpaEntry>()
        for ((k, v) in root) {
            val name = k as? String ?: continue
            val first = (v as? List<*>)?.firstOrNull() as? List<*> ?: continue
            val off = (first[0] as Number).toLong() xor key
            val len = (first[1] as Number).toLong() xor key
            val prefix = when (val pr = first.getOrNull(2)) {
                is ByteArray -> pr
                is String -> pr.toByteArray(Charsets.ISO_8859_1)
                else -> ByteArray(0)
            }
            list.add(RpaEntry(name, off, len, prefix))
        }
        return RpaIndex(version, list)
    }

    private fun <T> withChannel(cr: ContentResolver, uri: Uri, cacheDir: File, block: (FileChannel) -> T): T {
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Tidak bisa membuka file")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
            val ch = ins.channel
            val seekable = try { ch.position(0); ch.size() > 0 } catch (e: Exception) { false }
            if (seekable) return block(ch)
        }
        // Fallback untuk provider yang tidak mendukung seek (mis. cloud): salin dulu ke cache.
        val tmp = File.createTempFile("rpa_in", ".tmp", cacheDir)
        try {
            cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            RandomAccessFile(tmp, "r").use { raf -> return block(raf.channel) }
        } finally {
            tmp.delete()
        }
    }

    private class DirCache(val doc: DocumentFile) {
        val children: MutableMap<String, DocumentFile> by lazy {
            doc.listFiles().associateBy { it.name ?: "" }.toMutableMap()
        }

        fun subDir(name: String): DocumentFile {
            val ex = children[name]
            if (ex != null && ex.isDirectory) return ex
            if (ex != null) { ex.delete(); children.remove(name) }
            val d = doc.createDirectory(name) ?: throw IllegalStateException("Gagal membuat folder $name")
            children[name] = d
            return d
        }

        fun newFile(name: String): DocumentFile {
            children.remove(name)?.delete()
            val f = doc.createFile("application/octet-stream", name)
                ?: throw IllegalStateException("Gagal membuat file $name")
            children[name] = f
            return f
        }
    }

    private fun safeParts(name: String): List<String>? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts
    }

    private fun ensureDir(cache: HashMap<String, DirCache>, root: DocumentFile, parts: List<String>): DirCache {
        var key = ""
        var cur = cache.getOrPut("") { DirCache(root) }
        for (p in parts) {
            key = if (key.isEmpty()) p else "$key/$p"
            cur = cache[key] ?: DirCache(cur.subDir(p)).also { cache[key] = it }
        }
        return cur
    }

    /** Ekstrak seluruh isi arsip ke [outDir]. Mengembalikan jumlah file yang berhasil. */
    fun extract(
        cr: ContentResolver,
        archive: Uri,
        outDir: DocumentFile,
        cacheDir: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        onError: (String) -> Unit,
    ): Int = withChannel(cr, archive, cacheDir) { ch ->
        val idx = readIndex(ch)
        val dirs = HashMap<String, DirCache>()
        val buf = ByteBuffer.allocate(64 * 1024)
        var ok = 0
        var done = 0
        val total = idx.entries.size
        for (e in idx.entries) {
            try {
                val parts = safeParts(e.name)
                if (parts == null) {
                    onError("Dilewati (path tidak aman): ${e.name}")
                } else {
                    val dir = ensureDir(dirs, outDir, parts.dropLast(1))
                    val target = dir.newFile(parts.last())
                    cr.openOutputStream(target.uri, "wt")!!.use { out ->
                        if (e.prefix.isNotEmpty()) out.write(e.prefix)
                        var left = e.length - e.prefix.size
                        var pos = e.offset
                        while (left > 0) {
                            buf.clear()
                            if (left < buf.capacity()) buf.limit(left.toInt())
                            val n = ch.read(buf, pos)
                            if (n <= 0) throw EOFException("arsip terpotong")
                            out.write(buf.array(), 0, n)
                            pos += n
                            left -= n
                        }
                    }
                    ok++
                }
            } catch (ex: Exception) {
                onError("Gagal: ${e.name} (${ex.message})")
            }
            done++
            onProgress(done, total, e.name)
        }
        ok
    }

    // ---------------------------------------------------------------- tulis

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>) {
        for (f in dir.listFiles().sortedBy { it.name ?: "" }) {
            val n = f.name ?: continue
            val rel = if (prefix.isEmpty()) n else "$prefix/$n"
            if (f.isDirectory) collect(f, rel, out) else out.add(rel to f)
        }
    }

    /** Kemas folder [src] menjadi RPA-3.0 dan tulis ke [dest]. */
    fun build(
        cr: ContentResolver,
        src: DocumentFile,
        dest: Uri,
        cacheDir: File,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): Int {
        val files = ArrayList<Pair<String, DocumentFile>>()
        collect(src, "", files)
        require(files.isNotEmpty()) { "Folder kosong, tidak ada yang dikemas" }

        val tmp = File.createTempFile("rpa_build", ".tmp", cacheDir)
        try {
            val index = LinkedHashMap<String, LongArray>()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.write(ByteArray(HEADER_LEN)) // placeholder header
                val buf = ByteArray(64 * 1024)
                var n = 0
                for ((rel, doc) in files) {
                    val start = raf.filePointer
                    cr.openInputStream(doc.uri)!!.use { ins ->
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                        }
                    }
                    index[rel] = longArrayOf(start, raf.filePointer - start)
                    n++
                    onProgress(n, files.size, rel)
                }
                val indexOffset = raf.filePointer
                val bos = ByteArrayOutputStream()
                DeflaterOutputStream(bos).use { it.write(PickleWriter.index(index, KEY)) }
                raf.write(bos.toByteArray())

                val header = String.format(Locale.US, "RPA-3.0 %016x %08x\n", indexOffset, KEY)
                check(header.length == HEADER_LEN) { "Panjang header tidak cocok" }
                raf.seek(0)
                raf.write(header.toByteArray(Charsets.ISO_8859_1))
            }
            cr.openOutputStream(dest, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            return files.size
        } finally {
            tmp.delete()
        }
    }
}
