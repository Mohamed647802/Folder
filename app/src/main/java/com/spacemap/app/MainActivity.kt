package com.spacemap.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class Item(
    val file: File,
    val size: Long,
    val isDir: Boolean,
    val count: Int,
    val locked: Boolean,
    val virtual: Boolean = false,
    val label: String? = null
)

class MainActivity : Activity() {

    private val TRASH = ".SpaceMapTrash"
    private val REST = "__rest__"
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
    private var denied = false
    private var appsMode = false
    private var rootState = 0
    private var totalDev = 1L
    private var usedDev = 0L
    private var trashCounter = 0

    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
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

    private fun toolLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, -2, 1f)
        lp.marginEnd = dp(6)
        lp.bottomMargin = dp(6)
        return lp
    }

    // ---------- الصلاحيات ----------
    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == 0
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    private fun usageGranted(): Boolean {
        return try {
            val ao = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = ao.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
            m == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun askUsageAccess() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("صلاحية الوصول إلى بيانات الاستخدام")
            .setMessage("لعرض أحجام التطبيقات وبياناتها (Android/data) فعّل هذه الصلاحية لتطبيق «خريطة المساحة» ثم ارجع.")
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- الروت ----------
    private fun q(s: String): String = s.replace("'", "'\\''")

    private fun sh(cmd: String): List<String>? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun hasRoot(): Boolean {
        if (rootState == 0) {
            val r = sh("id")
            rootState = if (r != null && r.any { l -> l.contains("uid=0") }) 1 else 2
        }
        return rootState == 1
    }

    private fun rootSize(f: File): Long {
        val r = sh("du -sk '" + q(f.path) + "' 2>/dev/null | cut -f1")
        val v = r?.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        return v * 1024L
    }

    private fun rootList(dir: File): List<Item> {
        val cmd = "cd '" + q(dir.path) + "' 2>/dev/null && " +
                "for f in * .[!.]* ..?*; do " +
                "if [ -L \"\$f\" ]; then continue; fi; " +
                "[ -e \"\$f\" ] || continue; " +
                "if [ -d \"\$f\" ]; then echo \"d|\$(du -sk \"\$f\" 2>/dev/null | cut -f1)|\$f\"; " +
                "else echo \"f|\$(stat -c %s \"\$f\" 2>/dev/null)|\$f\"; fi; done"
        val out = ArrayList<Item>()
        val lines = sh(cmd) ?: return out
        for (ln in lines) {
            val p = ln.split("|", limit = 3)
            if (p.size < 3) continue
            val isD = p[0] == "d"
            var sz = p[1].trim().toLongOrNull() ?: 0L
            if (isD) sz *= 1024L
            out.add(Item(File(dir, p[2]), sz, isD, -1, false))
        }
        return out
    }

    private fun forceDelete(f: File): Boolean {
        if (f.deleteRecursively()) return true
        if (hasRoot()) {
            sh("rm -rf '" + q(f.path) + "'")
            return true
        }
        return false
    }

    // ---------- التطبيقات ----------
    private fun buildApps(dir: File): List<Item> {
        val out = ArrayList<Item>()
        try {
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val pm = packageManager
            for (ai in pm.getInstalledApplications(0)) {
                try {
                    val s = ssm.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle()
                    )
                    val total = s.appBytes + s.dataBytes + s.cacheBytes
                    val name = pm.getApplicationLabel(ai).toString() + "  (" + ai.packageName + ")"
                    out.add(Item(File(dir, ai.packageName), total, true, 0, false, true, name))
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun showApps() {
        if (!usageGranted()) {
            askUsageAccess()
            return
        }
        val dir = current
        selected.clear()
        items = emptyList()
        shown = emptyList()
        scanning = true
        appsMode = true
        scanId++
        val id = scanId
        refresh()
        ex.execute {
            val l = buildApps(dir)
            h.post {
                if (id == scanId) {
                    items = l
                    scanning = false
                    refresh()
                }
            }
        }
    }

    // ---------- وحدات التخزين ----------
    private fun volumes(): List<File> {
        val out = ArrayList<File>()
        out.add(root)
        try {
            val dirs = getExternalFilesDirs(null)
            for (d in dirs) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) {
                    val v = File(p.substring(0, i))
                    var exists = false
                    for (o in out) if (o.path == v.path) exists = true
                    if (!exists) out.add(v)
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun volumeOf(f: File): File {
        var best = root
        for (v in volumes()) {
            if (f.path.startsWith(v.path) && v.path.length >= best.path.length) best = v
        }
        return best
    }

    // ---------- الأيقونات ----------
    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i >= 0 && i < name.length - 1) name.substring(i + 1).lowercase() else ""
    }

    private fun iconFor(item: Item): String {
        if (item.virtual) return if (item.file.name == REST) "🧩" else "📱"
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

    // ---------- الواجهة ----------
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

        val pathRow = LinearLayout(this)
        pathRow.gravity = Gravity.CENTER_VERTICAL
        crumbScroll = HorizontalScrollView(this)
        crumbScroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbs = LinearLayout(this)
        crumbs.layoutDirection = View.LAYOUT_DIRECTION_LTR
        crumbs.gravity = Gravity.CENTER_VERTICAL
        crumbScroll.addView(crumbs)
        val copyBtn = btn("📋", Color.parseColor("#2B3A57"))
        copyBtn.setOnClickListener { copyPath() }
        pathRow.addView(crumbScroll, LinearLayout.LayoutParams(0, -2, 1f))
        pathRow.addView(copyBtn, LinearLayout.LayoutParams(-2, -2))
        val prLp = LinearLayout.LayoutParams(-1, -2)
        prLp.topMargin = dp(6)
        prLp.bottomMargin = dp(10)
        page.addView(pathRow, prLp)

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

        val bar1 = LinearLayout(this)
        val backBtn = btn("⬆ رجوع", Color.parseColor("#2B3A57"))
        backBtn.setOnClickListener { goBack() }
        sortBtn = btn("", Color.parseColor("#2B3A57"))
        sortBtn.setOnClickListener {
            asc = !asc
            refresh()
        }
        delBtn = btn("🗑 حذف", Color.parseColor("#C0392B"))
        delBtn.setOnClickListener { askDelete() }
        bar1.addView(backBtn, toolLp())
        bar1.addView(sortBtn, toolLp())
        bar1.addView(delBtn, toolLp())
        page.addView(bar1)

        val bar2 = LinearLayout(this)
        val storBtn = btn("💾 التخزين", Color.parseColor("#1F6F8B"))
        storBtn.setOnClickListener { showVolumes() }
        val appsBtn = btn("📱 التطبيقات", Color.parseColor("#6B4FA0"))
        appsBtn.setOnClickListener { showApps() }
        val trashBtn = btn("♻ السلة", Color.parseColor("#2E7D5B"))
        trashBtn.setOnClickListener { showTrash() }
        bar2.addView(storBtn, toolLp())
        bar2.addView(appsBtn, toolLp())
        bar2.addView(trashBtn, toolLp())
        page.addView(bar2)

        selAll = CheckBox(this)
        selAll.text = "تحديد الكل"
        selAll.setTextColor(Color.WHITE)
        selAll.buttonTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
        selAll.setOnClickListener {
            selected.clear()
            if (selAll.isChecked) {
                for (f in items) if (!f.virtual) selected.add(f.file.path)
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
            if (entry.virtual) {
                if (entry.file.name == REST) showApps()
                else Toast.makeText(this, entry.label ?: "", Toast.LENGTH_LONG).show()
            } else if (entry.isDir) {
                if (entry.locked) {
                    Toast.makeText(
                        this,
                        "🔒 محمي من النظام. يحتاج روت، أو افتح 📱 التطبيقات لرؤية الأحجام",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    navigate(entry.file)
                }
            } else {
                openFile(entry.file)
            }
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val entry = shown[p]
            if (!entry.virtual) {
                if (selected.contains(entry.file.path)) selected.remove(entry.file.path)
                else selected.add(entry.file.path)
                refresh()
            }
            true
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        if (!hasAccess()) requestAccess()
    }

    // ---------- المسار ----------
    private fun copyPath() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", current.absolutePath))
        Toast.makeText(this, "تم نسخ المسار", Toast.LENGTH_SHORT).show()
    }

    private fun crumb(label: String, path: String): TextView {
        val c = tv(label, 13f, Color.parseColor("#2DD4BF"), true)
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.background = shape(Color.parseColor("#1B2740"), 10)
        c.setOnClickListener { navigate(File(path)) }
        return c
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        crumbs.addView(crumb("/", "/"))
        val parts = current.absolutePath.split("/").filter { s -> s.isNotEmpty() }
        var acc = ""
        for (p in parts) {
            acc += "/" + p
            val target = acc
            crumbs.addView(tv(" › ", 14f, Color.parseColor("#8FA3BF")))
            crumbs.addView(crumb(p, target))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun navigate(dir: File) {
        stack.add(current)
        load(dir)
    }

    private fun showVolumes() {
        val vols = volumes()
        val names = ArrayList<String>()
        for (i in vols.indices) {
            names.add(if (i == 0) "📱 التخزين الداخلي" else "💾 بطاقة ذاكرة: " + vols[i].name)
        }
        names.add("🧭 الجذر /")
        names.add("🛠 /data (يحتاج روت)")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("اختر وحدة التخزين")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < vols.size) navigate(vols[which])
                else if (which == vols.size) navigate(File("/"))
                else navigate(File("/data"))
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (items.isEmpty() && !scanning && !denied) load(current)
        } else {
            statusTv.text = "⚠ اضغط هنا لمنح صلاحية الوصول لكل الملفات"
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        onResume()
    }

    // ---------- فتح الملفات ----------
    private fun launch(f: File) {
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

    private fun openFile(f: File) {
        if (f.canRead()) {
            launch(f)
            return
        }
        ex.execute {
            if (hasRoot()) {
                val tmp = File(cacheDir, "open_" + f.name)
                sh("cat '" + q(f.path) + "' > '" + q(tmp.path) + "'; chmod 666 '" + q(tmp.path) + "'")
                h.post { launch(tmp) }
            } else {
                h.post { Toast.makeText(this, "🔒 لا يمكن قراءة هذا الملف بدون روت", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun sortedItems(): List<Item> {
        return if (asc) items.sortedBy { x -> x.size } else items.sortedByDescending { x -> x.size }
    }

    private fun row(item: Item): View {
        val pct = item.size * 100.0 / totalDev
        val color = when {
            item.virtual && item.file.name == REST -> "#A78BFA"
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
        if (item.virtual) cb.visibility = View.GONE
        cb.setOnClickListener {
            if (cb.isChecked) selected.add(item.file.path) else selected.remove(item.file.path)
            refresh()
        }

        val icon = tv(iconFor(item), 22f, Color.WHITE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), 0, dp(8), 0)
        val title = item.label ?: item.file.name
        val nameTv = tv(title, 15f, Color.WHITE, true)
        nameTv.maxLines = 1
        nameTv.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(nameTv)
        val sub = if (item.virtual && item.file.name == REST)
            String.format("%.1f%% من الجهاز  •  اضغط لعرض تفصيل التطبيقات", pct)
        else if (item.virtual)
            String.format("%.1f%% من الجهاز  •  كود + بيانات + كاش", pct)
        else if (item.locked)
            "محمي من النظام (يحتاج روت)"
        else if (item.isDir && item.count < 0)
            String.format("%.1f%% من الجهاز  •  مقروء بالروت", pct)
        else if (item.isDir)
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
        buildCrumbs()
        sortBtn.text = if (asc) "⬆ تصاعدي" else "⬇ تنازلي"
        delBtn.text = "🗑 حذف (${selected.size})"
        delBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        var real = 0
        for (x in items) if (!x.virtual) real++
        selAll.isChecked = real > 0 && selected.size == real

        var tot = 0L
        for (x in items) tot += x.size
        statusTv.text = if (scanning) "⏳ جاري الحساب…  ${items.size} عنصر"
        else if (appsMode) "📱 التطبيقات المثبتة  •  ${items.size} تطبيق  •  ${Formatter.formatFileSize(this, tot)}"
        else if (denied) "🔒 هذا المجلد محمي (يحتاج روت، أو افتح 📱 التطبيقات)"
        else if (items.isEmpty()) "المجلد فارغ"
        else "الإجمالي هنا: ${Formatter.formatFileSize(this, tot)}  •  ${items.size} عنصر" +
                (if (rootState == 1) "  •  🔓 روت" else "")

        try {
            val st = StatFs(volumeOf(current).path)
            totalDev = st.totalBytes
            usedDev = st.totalBytes - st.availableBytes
        } catch (e: Exception) {
        }
        if (totalDev <= 0L) totalDev = 1L
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
        denied = false
        appsMode = false
        scanning = true
        scanId++
        val id = scanId
        refresh()
        ex.execute {
            val rootOk = hasRoot()
            val arr = dir.listFiles()
            val acc = ArrayList<Item>()
            var den = false
            var apps = false
            if (arr == null) {
                if (rootOk) {
                    acc.addAll(rootList(dir))
                } else if (dir.path.endsWith("/Android/data") && usageGranted()) {
                    acc.addAll(buildApps(dir))
                    apps = true
                } else {
                    den = true
                }
            } else {
                var last = 0L
                for (k in arr) {
                    if (id != scanId) return@execute
                    val isD = k.isDirectory
                    var cnt = 0
                    var lock = false
                    var size = 0L
                    var known = false
                    if (isD) {
                        val sub = k.list()
                        if (sub == null) {
                            if (rootOk) {
                                cnt = -1
                                size = rootSize(k)
                                known = true
                            } else {
                                lock = true
                                known = true
                            }
                        } else {
                            cnt = sub.size
                        }
                    }
                    if (!known) size = calcSize(k, id)
                    acc.add(Item(k, size, isD, cnt, lock))
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
            }
            if (id != scanId) return@execute
            // بند الباقي ليطابق المجموع المساحة المشغولة
            if (!apps && !den && dir.path == volumeOf(dir).path) {
                try {
                    val st = StatFs(dir.path)
                    val used = st.totalBytes - st.availableBytes
                    var sum = 0L
                    for (x in acc) sum += x.size
                    val rem = used - sum
                    if (rem > 0L) {
                        acc.add(
                            Item(
                                File(dir, REST), rem, true, 0, false, true,
                                "النظام وبيانات التطبيقات (غير مقروء)"
                            )
                        )
                    }
                } catch (e: Exception) {
                }
            }
            h.post {
                if (id == scanId) {
                    items = acc
                    denied = den
                    appsMode = apps
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
        if (appsMode) {
            load(current)
            return
        }
        if (stack.isNotEmpty()) {
            val prev = stack.removeAt(stack.size - 1)
            load(prev)
        } else {
            val par = current.parentFile
            if (par != null) load(par)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (appsMode || stack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    // ---------- الحذف والسلة ----------
    private fun inTrash(): Boolean = current.path.contains(TRASH)

    private fun askDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "حدد عنصراً أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val title = "⚠ ${selected.size} عنصر محدد"
        if (inTrash()) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setItems(arrayOf("💀 حذف نهائي (لا يمكن التراجع)", "إلغاء")) { _, which ->
                    if (which == 0) doDelete(1)
                }
                .show()
        } else {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setItems(
                    arrayOf(
                        "♻ نقل إلى السلة (يمكن الاسترجاع)",
                        "💀 حذف نهائي (لا يمكن التراجع)",
                        "🧹 حذف المحتويات الداخلية نهائياً",
                        "إلغاء"
                    )
                ) { _, which ->
                    if (which < 3) doDelete(which)
                }
                .show()
        }
    }

    private fun moveToTrash(f: File): Boolean {
        trashCounter++
        val dir = File(File(volumeOf(f), TRASH), System.currentTimeMillis().toString() + "_" + trashCounter)
        if (!dir.mkdirs()) return false
        try {
            File(dir, ".orig").writeText(f.absolutePath)
        } catch (e: Exception) {
            return false
        }
        val dest = File(dir, f.name)
        if (f.renameTo(dest)) return true
        return try {
            f.copyRecursively(dest, true) && f.deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    private fun doDelete(mode: Int) {
        val targets = selected.map { path -> File(path) }
        statusTv.text = "⏳ جاري الحذف…"
        ex.execute {
            var failed = 0
            for (f in targets) {
                if (mode == 0) {
                    if (!moveToTrash(f)) failed++
                } else if (mode == 1) {
                    if (!forceDelete(f)) failed++
                } else {
                    if (f.isDirectory) {
                        val kids = f.listFiles()
                        if (kids != null) {
                            for (k in kids) if (!forceDelete(k)) failed++
                        } else if (hasRoot()) {
                            sh("cd '" + q(f.path) + "' && rm -rf ./* ./.[!.]* 2>/dev/null")
                        } else {
                            failed++
                        }
                    }
                }
            }
            h.post {
                if (failed > 0) {
                    Toast.makeText(this, "تعذر حذف $failed عنصر", Toast.LENGTH_LONG).show()
                }
                load(current)
            }
        }
    }

    private fun trashEntries(): List<File> {
        val out = ArrayList<File>()
        for (v in volumes()) {
            val l = File(v, TRASH).listFiles()
            if (l != null) {
                for (d in l) if (d.isDirectory && entryFile(d) != null) out.add(d)
            }
        }
        return out
    }

    private fun entryFile(d: File): File? {
        val l = d.listFiles() ?: return null
        for (x in l) if (x.name != ".orig") return x
        return null
    }

    private fun showTrash() {
        val entries = trashEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "السلة فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        val names = Array(entries.size) { i -> "♻ " + entryFile(entries[i])!!.name }
        val checked = BooleanArray(entries.size)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("♻ السلة (${entries.size})")
            .setMultiChoiceItems(names, checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("استرجاع") { _, _ -> trashAction(entries, checked, true) }
            .setNegativeButton("حذف نهائي") { _, _ -> trashAction(entries, checked, false) }
            .setNeutralButton("إغلاق", null)
            .show()
    }

    private fun trashAction(entries: List<File>, checked: BooleanArray, restore: Boolean) {
        statusTv.text = "⏳ جاري التنفيذ…"
        ex.execute {
            for (i in entries.indices) {
                if (!checked[i]) continue
                val d = entries[i]
                val ef = entryFile(d)
                if (ef == null) {
                    d.deleteRecursively()
                    continue
                }
                if (restore) {
                    val orig = try {
                        File(d, ".orig").readText()
                    } catch (e: Exception) {
                        ""
                    }
                    if (orig.isNotEmpty()) {
                        var target = File(orig)
                        if (target.exists()) target = File(target.parentFile, "restored_" + target.name)
                        target.parentFile?.mkdirs()
                        if (!ef.renameTo(target)) {
                            try {
                                if (ef.copyRecursively(target, true)) ef.deleteRecursively()
                            } catch (e: Exception) {
                            }
                        }
                    }
                    if (!ef.exists()) d.deleteRecursively()
                } else {
                    d.deleteRecursively()
                }
            }
            h.post { load(current) }
        }
    }
}
