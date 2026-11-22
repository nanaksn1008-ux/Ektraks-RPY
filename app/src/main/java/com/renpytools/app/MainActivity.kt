package com.renpytools.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.renpytools.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private var archiveUri: Uri? = null
    private var outDirUri: Uri? = null
    private var srcDirUri: Uri? = null
    private var rpyUri: Uri? = null
    private var rpycText: String = ""
    private var rpycName: String = "script.rpy"

    private val presets = listOf(
        "Sunset" to R.drawable.bg_sunset,
        "Ocean" to R.drawable.bg_ocean,
        "Forest" to R.drawable.bg_forest,
        "Midnight" to R.drawable.bg_midnight,
        "Sakura" to R.drawable.bg_sakura,
    )

    private val snippets = linkedMapOf(
        "label" to "label nama_label:\n    ",
        "dialog" to "e \"Teks dialog\"\n",
        "menu" to "menu:\n    \"Pilihan A\":\n        jump a\n    \"Pilihan B\":\n        jump b\n",
        "scene" to "scene bg room\n",
        "show" to "show eileen happy\n",
        "jump" to "jump nama_label\n",
        "if" to "if variabel == True:\n    pass\nelse:\n    pass\n",
        "\$" to "\$ variabel = 0\n",
        "define" to "define e = Character(\"Eileen\")\n",
        "return" to "return\n",
    )

    // ------------------------------------------------------------ launchers

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { archiveUri = it; b.tvArchive.text = displayName(it) }
    }
    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { outDirUri = it; b.tvOut.text = displayName(it) }
    }
    private val pickSrc = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { srcDirUri = it; b.tvSrc.text = displayName(it) }
    }
    private val createRpa = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { dest ->
        val s = srcDirUri
        if (dest == null || s == null) return@registerForActivityResult
        val dir = DocumentFile.fromTreeUri(this, s) ?: return@registerForActivityResult
        runJob(b.btnBuild, b.progressBuild, b.tvLogBuild) { post, prog ->
            post("Mengemas file…")
            val n = RpaEngine.build(contentResolver, dir, dest, cacheDir) { d, t, _ -> prog(d, t) }
            post("Selesai. $n file dikemas (RPA-3.0).")
        }
    }

    private val openRpy = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    val t = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(it)!!.use { s -> s.readBytes().toString(Charsets.UTF_8) }
                    }
                    b.etRpy.setText(t)
                    rpyUri = it
                    b.tvRpyName.text = displayName(it)
                } catch (e: Exception) {
                    toast("Gagal membuka: ${e.message}")
                }
            }
        }
    }
    private val saveRpyAs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { rpyUri = it; b.tvRpyName.text = displayName(it); writeRpy(it) }
    }

    private val pickRpyc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { convertSingleRpyc(it) }
    }
    private val pickRpycDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { convertRpycFolder(it) }
    }
    private val saveRpycOut = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(it, "wt")!!.use { o -> o.write(rpycText.toByteArray(Charsets.UTF_8)) }
                    }
                    toast("Tersimpan")
                } catch (e: Exception) {
                    toast("Gagal menyimpan: ${e.message}")
                }
            }
        }
    }

    private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { saveCustomBg(it) }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        setupTabs()
        setupExtract()
        setupBuild()
        setupRpyEditor()
        setupRpyc()

        b.btnBackground.setOnClickListener { showBgDialog() }
        applyBackground()
    }

    private fun setupTabs() {
        val titles = listOf("Ekstrak RPA", "Buat RPA", "Editor RPY", "RPYC")
        val pages = listOf(b.pageExtract, b.pageBuild, b.pageRpy, b.pageRpyc)
        titles.forEach { b.tabs.addTab(b.tabs.newTab().setText(it)) }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pages.forEachIndexed { i, v -> v.visibility = if (i == tab.position) View.VISIBLE else View.GONE }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ------------------------------------------------------------ ekstrak / buat RPA

    private fun setupExtract() {
        b.btnPickArchive.setOnClickListener { pickArchive.launch(arrayOf("*/*")) }
        b.btnPickOut.setOnClickListener { pickOut.launch(null) }
        b.btnExtract.setOnClickListener {
            val a = archiveUri
            val o = outDirUri
            if (a == null || o == null) {
                toast("Pilih file .rpa dan folder tujuan dulu")
                return@setOnClickListener
            }
            val dir = DocumentFile.fromTreeUri(this, o) ?: return@setOnClickListener
            runJob(b.btnExtract, b.progressExtract, b.tvLogExtract) { post, prog ->
                post("Membaca indeks arsip…")
                val ok = RpaEngine.extract(contentResolver, a, dir, cacheDir, { d, t, _ -> prog(d, t) }, { post(it) })
                post("Selesai. $ok file diekstrak.")
            }
        }
    }

    private fun setupBuild() {
        b.btnPickSrc.setOnClickListener { pickSrc.launch(null) }
        b.btnBuild.setOnClickListener {
            if (srcDirUri == null) {
                toast("Pilih folder sumber dulu")
                return@setOnClickListener
            }
            createRpa.launch("game.rpa")
        }
    }

    // ------------------------------------------------------------ editor RPY

    private fun setupRpyEditor() {
        b.btnRpyNew.setOnClickListener {
            b.etRpy.setText(
                "# Skrip Ren'Py baru\n" +
                    "define e = Character(\"Eileen\")\n\n" +
                    "label start:\n\n" +
                    "    scene black\n" +
                    "    e \"Halo, ini cerita baru!\"\n\n" +
                    "    menu:\n" +
                    "        \"Pilihan 1\":\n" +
                    "            e \"Kamu memilih 1.\"\n" +
                    "        \"Pilihan 2\":\n" +
                    "            e \"Kamu memilih 2.\"\n\n" +
                    "    return\n"
            )
            rpyUri = null
            b.tvRpyName.text = "(file baru, belum disimpan)"
        }
        b.btnRpyOpen.setOnClickListener { openRpy.launch(arrayOf("*/*")) }
        b.btnRpySave.setOnClickListener {
            val u = rpyUri
            if (u != null) writeRpy(u) else saveRpyAs.launch("script.rpy")
        }
        b.btnRpySaveAs.setOnClickListener { saveRpyAs.launch("script.rpy") }

        for ((label, text) in snippets) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = label
            btn.isAllCaps = false
            btn.textSize = 12f
            btn.setOnClickListener {
                val et = b.etRpy
                val pos = et.selectionStart.coerceAtLeast(0)
                et.text.insert(pos, text)
            }
            b.snippetRow.addView(btn)
        }
    }

    private fun writeRpy(uri: Uri) {
        val text = b.etRpy.text.toString()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                toast("Tersimpan")
            } catch (e: Exception) {
                toast("Gagal menyimpan: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ RPYC

    private fun setupRpyc() {
        b.btnPickRpyc.setOnClickListener { pickRpyc.launch(arrayOf("*/*")) }
        b.btnPickRpycDir.setOnClickListener { pickRpycDir.launch(null) }
        b.btnSaveRpyc.setOnClickListener { saveRpycOut.launch(rpycName) }
    }

    private fun convertSingleRpyc(uri: Uri) {
        val name = displayName(uri)
        b.tvRpycOut.text = "Memproses $name…"
        b.btnSaveRpyc.isEnabled = false
        lifecycleScope.launch {
            try {
                val txt = withContext(Dispatchers.IO) {
                    RpycDecompiler.decompile(contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                }
                rpycText = txt
                rpycName = name.removeSuffix(".rpyc") + ".rpy"
                b.tvRpycOut.text = if (txt.length > 30000) txt.take(30000) + "\n… (tampilan dipotong, file simpanan utuh)" else txt
                b.btnSaveRpyc.isEnabled = true
            } catch (e: Exception) {
                b.tvRpycOut.text = "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun collectRpyc(dir: DocumentFile, out: MutableList<Pair<DocumentFile, DocumentFile>>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) collectRpyc(f, out)
            else if (f.name?.endsWith(".rpyc") == true) out.add(dir to f)
        }
    }

    private fun convertRpycFolder(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        runJob(b.btnPickRpycDir, b.progressRpyc, b.tvRpycOut) { post, prog ->
            val files = ArrayList<Pair<DocumentFile, DocumentFile>>()
            collectRpyc(root, files)
            post("Ditemukan ${files.size} file .rpyc")
            var ok = 0
            files.forEachIndexed { i, (parent, f) ->
                try {
                    val txt = RpycDecompiler.decompile(contentResolver.openInputStream(f.uri)!!.use { it.readBytes() })
                    val base = f.name!!.removeSuffix(".rpyc")
                    parent.findFile("$base.rpy")?.delete()
                    val out = parent.createFile("application/octet-stream", "$base.rpy")
                        ?: throw IllegalStateException("tidak bisa membuat $base.rpy")
                    contentResolver.openOutputStream(out.uri, "wt")!!.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                    ok++
                } catch (e: Exception) {
                    post("Gagal ${f.name}: ${e.message ?: e.javaClass.simpleName}")
                }
                prog(i + 1, files.size)
            }
            post("Selesai: $ok/${files.size} file dikonversi. File .rpy dibuat di folder yang sama.")
        }
    }

    // ------------------------------------------------------------ background

    private fun showBgDialog() {
        val items = presets.map { it.first } + listOf("Acak", "Pilih dari galeri…")
        MaterialAlertDialogBuilder(this)
            .setTitle("Ganti background")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < presets.size -> {
                        prefs.edit().putString("bg", "p$i").apply()
                        applyBackground()
                    }
                    i == presets.size -> {
                        prefs.edit().putString("bg", "p${presets.indices.random()}").apply()
                        applyBackground()
                    }
                    else -> pickBg.launch("image/*")
                }
            }
            .show()
    }

    private fun applyBackground() {
        val mode = prefs.getString("bg", "p0") ?: "p0"
        if (mode == "custom") {
            val f = File(filesDir, "bg_custom.jpg")
            if (f.exists()) {
                b.bgImage.setImageBitmap(BitmapFactory.decodeFile(f.path))
                b.bgImage.visibility = View.VISIBLE
                return
            }
        }
        val idx = mode.removePrefix("p").toIntOrNull()?.coerceIn(0, presets.lastIndex) ?: 0
        b.bgImage.visibility = View.GONE
        b.root.setBackgroundResource(presets[idx].second)
    }

    private fun saveCustomBg(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
                        ?: return@withContext false
                    File(filesDir, "bg_custom.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                prefs.edit().putString("bg", "custom").apply()
                applyBackground()
            } else {
                toast("Gagal memuat gambar")
            }
        }
    }

    // ------------------------------------------------------------ util

    private fun runJob(
        btn: View,
        bar: ProgressBar,
        log: TextView,
        work: (post: (String) -> Unit, prog: (Int, Int) -> Unit) -> Unit,
    ) {
        btn.isEnabled = false
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        log.text = ""
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    work(
                        { m -> runOnUiThread { log.append(m + "\n") } },
                        { d, t ->
                            runOnUiThread {
                                bar.isIndeterminate = false
                                bar.max = t
                                bar.progress = d
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                log.append("ERROR: ${e.message ?: e.javaClass.simpleName}\n")
            } finally {
                btn.isEnabled = true
                bar.visibility = View.GONE
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (DocumentsContract.isTreeUri(uri)) {
            return DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "folder"
        }
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
