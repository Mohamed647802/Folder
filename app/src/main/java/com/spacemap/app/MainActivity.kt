package com.spacemap.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class Item(val file: File, val size: Long, val isDir: Boolean)

class MainActivity : Activity() {

    private val ex = Executors.newFixedThreadPool(2)
    private val h = Handler(Looper.getMainLooper())
    private val root: File = Environment.getExternalStorageDirectory()
    private var current: File = root
    private val stack = ArrayList<File>()
    private var items: List<Item> = emptyList()
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(t: String, bg: Int) = tv(t, 13f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = shape(bg, 12)
    }

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == 0

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#0B1020")
        window.navigationBarColor = Color.parseColor("#0B1020")

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0B1020"), Color.parseColor("#141C33"), Color.parseColor("#0E2A3A")))
            setPadding(dp(14), dp(34), dp(14), dp(10))
        }

        page.addView(tv("🗂  خريطة المساحة", 24f, Color.WHITE, true))
        pathTv = tv("", 12f, Color.parseColor("#8FA3BF")).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(4), 0, dp(10))
        }
        page.addView(pathTv)

        // بطاقة مساحة الجهاز الكلية
        val devCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(Color.parseColor("#18202F"), 18, Color.parseColor("#26324A"))
        }
        devTv = tv("", 14f, Color.WHITE, true)
        devBar = LinearLayout(this).apply {
            weightSum = 100f
            background = shape(Color.parseColor("#2A3550"), 6)
        }
        devCard.addView(devTv)
        devCard.addView(devBar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(8) })
        page.addView(devCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // شريط الأدوات
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = btn("⬆ رجوع", Color.parseColor("#2B3A57")).apply { setOnClickListener { goBack() } }
        sortBtn = btn("", Color.parseColor("#2B3A57")).apply {
            setOnClickListener { asc = !asc; refresh() }
        }
        delBtn = btn("🗑 حذف", Color.parseColor("#C0392B")).apply { setOnClickListener { askDelete() } }
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) } }
        bar.addView(back, lp()); bar.addView(sortBtn, lp()); bar.addView(delBtn, lp())
        page.addView(bar)

        selAll = CheckBox(this).apply {
            text = "تحديد الكل"; setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
            setOnClickListener {
                selected.clear()
                if (isChecked) items.forEach { selected.add(it.file.path) }
                refresh()
            }
        }
        page.addView(selAll)

        statusTv = tv("", 12f, Color.parseColor("#2DD4BF"))
        page.addView(statusTv)

        list = ListView(this).apply { divider = null; dividerHeight = 0; setPadding(0, dp(4), 0, 0) }
        adapter = object : BaseAdapter() {
            override fun getCount() = view().size
            override fun getItem(p: Int) = view()[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup) = row(view()[p])
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val it = view()[p]
            if (it.isDir) { stack.add(current); load(it.file) }
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        statusTv.setOnClickListener { if (!hasAccess()) requestAccess() }
        if (!hasAccess()) requestAccess()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) { if (items.isEmpty() && !scanning) load(current) }
        else statusTv.text = "⚠ اضغط هنا لمنح صلاحية الوصول لكل الملفات"
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); onResume()
    }

    private fun view() = if (asc) items.sortedBy { it.size } else items.sortedByDescending { it.size }

    private fun row(it: Item): View {
        val pct = it.size * 100.0 / totalDev
        val color = when {
            pct > 25 -> "#EF4444"; pct > 10 -> "#FB923C"; pct > 3 -> "#38BDF8"; else -> "#2DD4BF"
        }
        val isSel = selected.contains(it.file.path)
        val wrap = FrameLayout(this).apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(12))
            background = shape(Color.parseColor(if (isSel) "#1E3A5F" else "#18202F"), 16,
                Color.parseColor(if (isSel) "#2DD4BF" else "#26324A"))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val cb = CheckBox(this).apply {
            isChecked = isSel
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
            setOnClickListener {
                if (isChecked) selected.add(it.file.path) else selected.remove(it.file.path)
                refresh()
            }
        }
        val icon = tv(if (it.isDir) "📁" else "📄", 22f, Color.WHITE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        col.addView(tv(it.file.name, 15f, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        col.addView(tv(String.format("%.1f%% من مساحة الجهاز", pct), 11f, Color.parseColor("#8FA3BF")))
        val sz = tv(Formatter.formatFileSize(this, it.size), 14f, Color.parseColor(color), true)
        top.addView(cb); top.addView(icon); top.addView(col, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(sz)
        card.addView(top)

        val w = pct.coerceIn(1.5, 100.0).toFloat()
        val track = LinearLayout(this).apply {
            weightSum = 100f; background = shape(Color.parseColor("#2A3550"), 6)
        }
        track.addView(View(this).apply { background = shape(Color.parseColor(color), 6) },
            LinearLayout.LayoutParams(0, -1, w))
        track.addView(View(this), LinearLayout.LayoutParams(0, -1, 100f - w))
        card.addView(track, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        wrap.addView(card)
        return wrap
    }

    private fun refresh() {
        pathTv.text = current.path
        sortBtn.text = if (asc) "⬆ تصاعدي" else "⬇ تنازلي"
        delBtn.text = "🗑 حذف (${selected.size})"
        delBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        selAll.isChecked = items.isNotEmpty() && selected.size == items.size
        val tot = items.sumOf { it.size }
        statusTv.text = if (scanning) "⏳ جاري الحساب…  ${items.size} عنصر"
        else "الإجمالي هنا: ${Formatter.formatFileSize(this, tot)}  •  ${items.size} عنصر"
        try {
            val st = StatFs(root.path)
            totalDev = st.totalBytes; usedDev = st.totalBytes - st.availableBytes
        } catch (e: Exception) {}
        val up = usedDev * 100f / totalDev
        devTv.text = "المستخدم ${Formatter.formatFileSize(this, usedDev)} من " +
            "${Formatter.formatFileSize(this, totalDev)}  (${up.toInt()}%)"
        devBar.removeAllViews()
        devBar.addView(View(this).apply { background = shape(Color.parseColor("#38BDF8"), 6) },
            LinearLayout.LayoutParams(0, -1, up.coerceIn(1f, 100f)))
        devBar.addView(View(this), LinearLayout.LayoutParams(0, -1, 100f - up.coerceIn(1f, 100f)))
        adapter.notifyDataSetChanged()
    }

    private fun load(dir: File) {
        current = dir; selected.clear(); items = emptyList(); scanning = true
        val id = ++scanId
        refresh()
        ex.execute {
            val kids = dir.listFiles()?.toList() ?: emptyList()
            val acc = ArrayList<Item>()
            var last = 0L
            for (k in kids) {
                if (id != scanId) return@execute
                acc.add(Item(k, size(k, id), k.isDirectory))
                val now = SystemClock.uptimeMillis()
                if (now - last > 400) {
                    last = now
                    val snap = ArrayList(acc)
                    h.post { if (id == scanId) { items = snap; refresh() } }
                }
            }
            h.post { if (id == scanId) { items = acc; scanning = false; refresh() } }
        }
    }

    private fun size(f: File, id: Int): Long {
        if (id != scanId) return 0
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return 0
        if (f.isFile) return f.length()
        var s = 0L
        f.listFiles()?.forEach { s += size(it, id) }
        return s
    }

    private fun goBack() {
        if (stack.isNotEmpty()) load(stack.removeAt(stack.size - 1))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun askDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "حدد عنصراً أولاً", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("⚠ حذف ${selected.size} عنصر (لا يمكن التراجع)")
            .setItems(arrayOf("حذف العناصر بالكامل", "حذف المحتويات الداخلية فقط", "إلغاء")) { _, w ->
                if (w < 2) doDelete(w == 1)
            }.show()
    }

    private fun doDelete(contentsOnly: Boolean) {
        val targets = selected.map { File(it) }
        statusTv.text = "⏳ جاري الحذف…"
        ex.execute {
            for (f in targets) {
                if (contentsOnly) { if (f.isDirectory) f.listFiles()?.forEach { it.deleteRecursively() } }
                else f.deleteRecursively()
            }
            h.post { load(current) }
        }
    }
}
