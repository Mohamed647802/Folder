package com.spacemap.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class Item(val file: File, val size: Long, val isDir: Boolean, val count: Int, val locked: Boolean)

class MainActivity : Activity() {

    private val ex = Executors.newFixedThreadPool(2)
    private val h = Handler(Looper.getMainLooper())
    private val root: File = Environment.getExternalStorageDirectory()
    private var current: File = root
    private val stack = ArrayList<File>()
    private var items: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private val selected = HashSet<String>()
    private var asc = false
    private var scanId = 0
    private var scanning = false
    private var totalDev = 1L
    private var usedDev = 0L

    private lateinit var pathTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var devTv: TextView
    private lateinit var devBar: LinearLayout
    private lateinit var sortBtn: TextView
    private lateinit var delBtn: TextView
    private lateinit var selAll: CheckBox
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val x = TextView(this)
        x.text = t
        x.textSize = size
        x.setTextColor(color)
        if (bold) x.typeface = Typeface.DEFAULT_BOLD
        return x
    }

    private fun btn(t: String, bg: Int): TextView {
        val x = tv(t, 13f, Color.WHITE, true)
        x.gravity = Gravity.CENTER
        x.setPadding(dp(8), dp(10), dp(8), dp(10))
        x.background = shape(bg, 12)
        return x
    }

    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == 0
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i >= 0 && i < name.length - 1) name.substring(i + 1).lowercase() else ""
    }

    private fun iconFor(item: Item): String {
        if (item.locked) return "🔒"
        if (item.isDir) return if (item.count == 0) "📂" else "📁"
        return when (extOf(item.file.name)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼"
            "mp4", "mkv", "avi", "mov", "3gp", "webm" -> "🎬"
            "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus" -> "🎵"
            "apk", "xapk", "apks" -> "📦"
            "zip", "rar", "7z", "tar", "gz" -> "🗜"
            "pdf" -> "📕"
            "doc", "docx", "txt", "rtf", "odt" -> "📝"
            "xls", "xlsx", "csv" -> "📊"
            "ppt", "pptx" -> "📽"
            else -> "📄"
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#0B1020")
        window.navigationBarColor = Color.parseColor("#0B1020")

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.layoutDirection = View.LAYOUT_DIRECTION_RTL
        page.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#0B1020"),
                Color.parseColor("#141C33"),
                Color.parseColor("#0E2A3A")
            )
        )
        page.setPadding(dp(14), dp(34), dp(14), dp(10))

        page.addView(tv("🗂  خريطة المساحة", 24f, Color.WHITE, true))

        pathTv = tv("", 12f, Color.parseColor("#8FA3BF"))
        pathTv.maxLines = 1
        pathTv.ellipsize = TextUtils.TruncateAt.MIDDLE
        pathTv.setPadding(0, dp(4), 0, dp(10))
        page.addView(pathTv)

        val devCard = LinearLayout(this)
        devCard.orientation = LinearLayout.VERTICAL
        devCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        devCard.background = shape(Color.parseColor("#18202F"), 18, Color.parseColor("#26324A"))
        devTv = tv("", 14f, Color.WHITE, true)
        devBar = LinearLayout(this)
        devBar.weightSum = 100f
        devBar.background = shape(Color.parseColor("#2A3550"), 6)
        devCard.addView(devTv)
        val barLp = LinearLayout.LayoutParams(-1, dp(12))
        barLp.topMargin = dp(8)
        devCard.addView(devBar, barLp)
        val cardLp = LinearLayout.LayoutParams(-1, -2)
        cardLp.bottomMargin = dp(10)
        page.addView(devCard, cardLp)

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL

        val backBtn = btn("⬆ رجوع", Color.parseColor("#2B3A57"))
        backBtn.setOnClickListener { goBack() }

        sortBtn = btn("", Color.parseColor("#2B3A57"))
        sortBtn.setOnClickListener {
            asc = !asc
            refresh()
        }

        delBtn = btn("🗑 حذف", Color.parseColor("#C0392B"))
        delBtn.setOnClickListener { askDelete() }

        bar.addView(backBtn, toolLp())
        bar.addView(sortBtn, toolLp())
        bar.addView(delBtn, toolLp())
        page.addView(bar)

        selAll = CheckBox(this)
        selAll.text = "تحديد الكل"
        selAll.setTextColor(Color.WHITE)
        selAll.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
        selAll.setOnClickListener {
            selected.clear()
            if (selAll.isChecked) {
                for (f in items) selected.add(f.file.path)
            }
            refresh()
        }
        page.addView(selAll)

        statusTv = tv("", 12f, Color.parseColor("#2DD4BF"))
        statusTv.setOnClickListener { if (!hasAccess()) requestAccess() }
        page.addView(statusTv)

        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.setPadding(0, dp(4), 0, 0)

        adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(p: Int): Any = shown[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View = row(shown[p])
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val entry = shown[p]
            if (entry.isDir) {
                if (entry.locked) {
                    Toast.makeText(this, "🔒 المجلد محمي ولا يمكن قراءته", Toast.LENGTH_SHORT).show()
                } else {
                    stack.add(current)
                    load(entry.file)
                }
            } else {
                openFile(entry.file)
            }
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val entry = shown[p]
            if (selected.contains(entry.file.path)) selected.remove(entry.file.path)
            else selected.add(entry.file.path)
            refresh()
            true
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        if (!hasAccess()) requestAccess()
    }

    private fun toolLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        lp.marginEnd = dp(6)
        return lp
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (items.isEmpty() && !scanning) load(current)
        } else {
            statusTv.text = "⚠ اضغط هنا لمنح صلاحية الوصول لكل الملفات"
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        onResume()
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(f.name)) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد تطبيق يفتح هذا النوع من الملفات", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الملف", Toast.LENGTH_LONG).show()
        }
    }

    private fun sortedItems(): List<Item> {
        return if (asc) items.sortedBy { x -> x.size } else items.sortedByDescending { x -> x.size }
    }

    private fun row(item: Item): View {
        val pct = item.size * 100.0 / totalDev
        val color = when {
            pct > 25 -> "#EF4444"
            pct > 10 -> "#FB923C"
            pct > 3 -> "#38BDF8"
            else -> "#2DD4BF"
        }
        val isSel = selected.contains(item.file.path)

        val wrap = FrameLayout(this)
        wrap.setPadding(dp(2), dp(4), dp(2), dp(4))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(10), dp(12), dp(12))
        card.background = shape(
            Color.parseColor(if (isSel) "#1E3A5F" else "#18202F"), 16,
            Color.parseColor(if (isSel) "#2DD4BF" else "#26324A")
        )

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL

        val cb = CheckBox(this)
        cb.isChecked = isSel
        cb.isFocusable = false
        cb.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
        cb.setOnClickListener {
            if (cb.isChecked) selected.add(item.file.path) else selected.remove(item.file.path)
            refresh()
        }

        val icon = tv(iconFor(item), 22f, Color.WHITE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), 0, dp(8), 0)
        val nameTv = tv(item.file.name, 15f, Color.WHITE, true)
        nameTv.maxLines = 1
        nameTv.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(nameTv)
        val sub = if (item.isDir)
            String.format("%.1f%% من الجهاز  •  %d عنصر", pct, item.count)
        else
            String.format("%.1f%% من الجهاز  •  اضغط للفتح", pct)
        col.addView(tv(sub, 11f, Color.parseColor("#8FA3BF")))

        val sz = tv(Formatter.formatFileSize(this, item.size), 14f, Color.parseColor(color), true)

        top.addView(cb)
        top.addView(icon)
        top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(sz)
        card.addView(top)

        val w = pct.coerceIn(1.5, 100.0).toFloat()
        val track = LinearLayout(this)
        track.weightSum = 100f
        track.background = shape(Color.parseColor("#2A3550"), 6)
        val fill = View(this)
        fill.background = shape(Color.parseColor(color), 6)
        track.addView(fill, LinearLayout.LayoutParams(0, -1, w))
        track.addView(View(this), LinearLayout.LayoutParams(0, -1, 100f - w))
        val trackLp = LinearLayout.LayoutParams(-1, dp(10))
        trackLp.topMargin = dp(8)
        card.addView(track, trackLp)

        wrap.addView(card)
        return wrap
    }

    private fun refresh() {
        pathTv.text = current.path
        sortBtn.text = if (asc) "⬆ تصاعدي" else "⬇ تنازلي"
        delBtn.text = "🗑 حذف (${selected.size})"
        delBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        selAll.isChecked = items.isNotEmpty() && selected.size == items.size

        var tot = 0L
        for (x in items) tot += x.size
        statusTv.text = if (scanning) "⏳ جاري الحساب…  ${items.size} عنصر"
        else if (items.isEmpty()) "المجلد فارغ"
        else "الإجمالي هنا: ${Formatter.formatFileSize(this, tot)}  •  ${items.size} عنصر"

        try {
            val st = StatFs(root.path)
            totalDev = st.totalBytes
            usedDev = st.totalBytes - st.availableBytes
        } catch (e: Exception) {
        }
        val up = usedDev * 100f / totalDev
        devTv.text = "المستخدم ${Formatter.formatFileSize(this, usedDev)} من " +
                "${Formatter.formatFileSize(this, totalDev)}  (${up.toInt()}%)"
        val upc = up.coerceIn(1f, 100f)
        devBar.removeAllViews()
        val devFill = View(this)
        devFill.background = shape(Color.parseColor("#38BDF8"), 6)
        devBar.addView(devFill, LinearLayout.LayoutParams(0, -1, upc))
        devBar.addView(View(this), LinearLayout.LayoutParams(0, -1, 100f - upc))

        shown = sortedItems()
        adapter.notifyDataSetChanged()
    }

    private fun load(dir: File) {
        current = dir
        selected.clear()
        items = emptyList()
        shown = emptyList()
        scanning = true
        scanId++
        val id = scanId
        refresh()
        ex.execute {
            val kidsArr = dir.listFiles()
            val kids = kidsArr?.toList() ?: emptyList()
            val acc = ArrayList<Item>()
            var last = 0L
            for (k in kids) {
                if (id != scanId) return@execute
                val isD = k.isDirectory
                var cnt = 0
                var lock = false
                if (isD) {
                    val sub = k.list()
                    if (sub == null) lock = true else cnt = sub.size
                }
                acc.add(Item(k, calcSize(k, id), isD, cnt, lock))
                val now = SystemClock.uptimeMillis()
                if (now - last > 400) {
                    last = now
                    val snap = ArrayList(acc)
                    h.post {
                        if (id == scanId) {
                            items = snap
                            refresh()
                        }
                    }
                }
            }
            h.post {
                if (id == scanId) {
                    items = acc
                    scanning = false
                    refresh()
                }
            }
        }
    }

    private fun calcSize(f: File, id: Int): Long {
        if (id != scanId) return 0L
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return 0L
        } catch (e: Exception) {
            return 0L
        }
        if (f.isFile) return f.length()
        var s = 0L
        val kids = f.listFiles()
        if (kids != null) {
            for (k in kids) s += calcSize(k, id)
        }
        return s
    }

    private fun goBack() {
        if (stack.isNotEmpty()) {
            val prev = stack.removeAt(stack.size - 1)
            load(prev)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun askDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "حدد عنصراً أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("⚠ حذف ${selected.size} عنصر (لا يمكن التراجع)")
            .setItems(arrayOf("حذف العناصر بالكامل", "حذف المحتويات الداخلية فقط", "إلغاء")) { _, which ->
                if (which < 2) doDelete(which == 1)
            }
            .show()
    }

    private fun doDelete(contentsOnly: Boolean) {
        val targets = selected.map { path -> File(path) }
        statusTv.text = "⏳ جاري الحذف…"
        ex.execute {
            for (f in targets) {
                if (contentsOnly) {
                    if (f.isDirectory) {
                        val kids = f.listFiles()
                        if (kids != null) {
                            for (k in kids) k.deleteRecursively()
                        }
                    }
                } else {
                    f.deleteRecursively()
                }
            }
            h.post { load(current) }
        }
    }
}
