@file:OptIn(ExperimentalFoundationApi::class)

package com.example.pdfreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    /** Set by the UI when tr("vol_keys") is on. */
    var volumeHandler: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial: Uri? = intent?.data
        Loc.lang = AppLang.fromCode(Prefs(this).langCode)
        setContent {
            val dir = if (Loc.lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides dir) { PdfApp(initial) }
        }
    }

    private fun isVolume(k: Int) = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val h = volumeHandler
        if (h != null && isVolume(keyCode)) {
            if (event?.repeatCount == 0) h(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeHandler != null && isVolume(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
}

/* ---------- Reading modes (background colour / dark mode) ---------- */

private fun tint(r: Float, g: Float, b: Float) = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

enum class ReadMode(val key: String, val bg: Color) {
    NORMAL("mode_normal", Color(0xFFBDBDBD)),
    SEPIA("mode_sepia", Color(0xFFB8A887)),
    GREEN("mode_green", Color(0xFF9DB59D)),
    DARK("mode_dark", Color(0xFF000000));

    val label: String get() = tr(key)

    fun filter(): ColorFilter? = when (this) {
        NORMAL -> null
        SEPIA -> tint(0.98f, 0.93f, 0.76f)
        GREEN -> tint(0.80f, 0.92f, 0.80f)
        DARK -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.9f, 0f, 0f, 0f, 230f,
                    0f, -0.9f, 0f, 0f, 230f,
                    0f, 0f, -0.9f, 0f, 230f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}

/* ---------- Saved data ---------- */

data class RecentItem(
    val id: String,
    val uri: String,
    val name: String,
    val page: Int,
    val pages: Int,
    val time: Long,
    val pinned: Boolean
)

data class MergeSrc(val uri: Uri, val name: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pdf_reader", Context.MODE_PRIVATE)

    // ----- settings -----
    var mode: Int
        get() = sp.getInt("mode_v2", 3)
        set(v) = sp.edit().putInt("mode_v2", v).apply()
    var shieldOn: Boolean
        get() = sp.getBoolean("shield_on", false)
        set(v) = sp.edit().putBoolean("shield_on", v).apply()
    var shieldLevel: Float
        get() = sp.getFloat("shield_level", 0.5f)
        set(v) = sp.edit().putFloat("shield_level", v).apply()
    var fullScreen: Boolean
        get() = sp.getBoolean("full_screen", true)
        set(v) = sp.edit().putBoolean("full_screen", v).apply()
    var keepOn: Boolean
        get() = sp.getBoolean("keep_on", true)
        set(v) = sp.edit().putBoolean("keep_on", v).apply()
    var autoBright: Boolean
        get() = sp.getBoolean("auto_bright", true)
        set(v) = sp.edit().putBoolean("auto_bright", v).apply()
    var brightness: Float
        get() = sp.getFloat("brightness", 0.6f)
        set(v) = sp.edit().putFloat("brightness", v).apply()
    var volumeKeys: Boolean
        get() = sp.getBoolean("volume_keys", false)
        set(v) = sp.edit().putBoolean("volume_keys", v).apply()
    var autoDark: Boolean
        get() = sp.getBoolean("auto_dark", false)
        set(v) = sp.edit().putBoolean("auto_dark", v).apply()
    var resumeLast: Boolean
        get() = sp.getBoolean("resume_last", false)
        set(v) = sp.edit().putBoolean("resume_last", v).apply()
    var horizontal: Boolean
        get() = sp.getBoolean("horizontal", false)
        set(v) = sp.edit().putBoolean("horizontal", v).apply()
    var scrollSpeed: Float
        get() = sp.getFloat("scroll_speed", 120f)
        set(v) = sp.edit().putFloat("scroll_speed", v).apply()
    var lastUri: String?
        get() = sp.getString("last_uri", null)
        set(v) = sp.edit().putString("last_uri", v).apply()
    var langCode: String
        get() = sp.getString("lang", null) ?: AppLang.fromSystem().code
        set(v) = sp.edit().putString("lang", v).apply()
    var menuBg: String?
        get() = sp.getString("menu_bg", null)
        set(v) = sp.edit().putString("menu_bg", v).apply()

    // ----- per file -----
    fun getPage(key: String): Int = sp.getInt("page_$key", 0)
    fun setPage(key: String, page: Int) = sp.edit().putInt("page_$key", page).apply()

    fun getZoom(key: String): Float = sp.getFloat("zoom_$key", 1f)
    fun setZoom(key: String, z: Float) = sp.edit().putFloat("zoom_$key", z).apply()

    fun getBookmarks(key: String): List<Int> =
        (sp.getStringSet("bm_$key", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .sorted()

    fun setBookmarks(key: String, pages: Collection<Int>) =
        sp.edit().putStringSet("bm_$key", pages.map { it.toString() }.toSet()).apply()

    fun getNote(key: String, page: Int): String = sp.getString("note_${key}_$page", "") ?: ""
    fun setNote(key: String, page: Int, note: String) {
        if (note.isBlank()) sp.edit().remove("note_${key}_$page").apply()
        else sp.edit().putString("note_${key}_$page", note).apply()
    }

    // ----- recent files -----
    fun getRecents(): List<RecentItem> = runCatching {
        val arr = JSONArray(sp.getString("recents", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentItem(
                o.getString("id"), o.getString("uri"), o.getString("name"),
                o.optInt("page", 0), o.optInt("pages", 0),
                o.optLong("time", 0L), o.optBoolean("pinned", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun putRecents(list: List<RecentItem>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("uri", r.uri).put("name", r.name)
                    .put("page", r.page).put("pages", r.pages)
                    .put("time", r.time).put("pinned", r.pinned)
            )
        }
        sp.edit().putString("recents", arr.toString()).apply()
    }

    fun touchRecent(item: RecentItem) {
        val old = getRecents()
        val prev = old.firstOrNull { it.id == item.id }
        val all = listOf(item.copy(pinned = prev?.pinned ?: false)) + old.filter { it.id != item.id }
        val keepIds = all.filter { !it.pinned }.take(30).map { it.id }.toSet()
        putRecents(all.filter { it.pinned || it.id in keepIds })
    }

    fun updateRecentPage(id: String, page: Int) =
        putRecents(getRecents().map { if (it.id == id) it.copy(page = page) else it })

    fun togglePin(id: String) =
        putRecents(getRecents().map { if (it.id == id) it.copy(pinned = !it.pinned) else it })

    fun removeRecent(id: String) = putRecents(getRecents().filter { it.id != id })
}

/** All user settings as Compose state (saved to Prefs when changed). */
class Settings(private val prefs: Prefs) {
    private val mMode = mutableIntStateOf(prefs.mode)
    var modeIdx: Int
        get() = mMode.intValue
        set(v) { mMode.intValue = v; prefs.mode = v }

    private val mShield = mutableStateOf(prefs.shieldOn)
    var shieldOn: Boolean
        get() = mShield.value
        set(v) { mShield.value = v; prefs.shieldOn = v }

    private val mShieldLevel = mutableFloatStateOf(prefs.shieldLevel)
    var shieldLevel: Float
        get() = mShieldLevel.floatValue
        set(v) { mShieldLevel.floatValue = v; prefs.shieldLevel = v }

    private val mFull = mutableStateOf(prefs.fullScreen)
    var fullScreen: Boolean
        get() = mFull.value
        set(v) { mFull.value = v; prefs.fullScreen = v }

    private val mKeepOn = mutableStateOf(prefs.keepOn)
    var keepOn: Boolean
        get() = mKeepOn.value
        set(v) { mKeepOn.value = v; prefs.keepOn = v }

    private val mAutoBright = mutableStateOf(prefs.autoBright)
    var autoBright: Boolean
        get() = mAutoBright.value
        set(v) { mAutoBright.value = v; prefs.autoBright = v }

    private val mBrightness = mutableFloatStateOf(prefs.brightness)
    var brightness: Float
        get() = mBrightness.floatValue
        set(v) { mBrightness.floatValue = v; prefs.brightness = v }

    private val mVolume = mutableStateOf(prefs.volumeKeys)
    var volumeKeys: Boolean
        get() = mVolume.value
        set(v) { mVolume.value = v; prefs.volumeKeys = v }

    private val mAutoDark = mutableStateOf(prefs.autoDark)
    var autoDark: Boolean
        get() = mAutoDark.value
        set(v) { mAutoDark.value = v; prefs.autoDark = v }

    private val mResume = mutableStateOf(prefs.resumeLast)
    var resumeLast: Boolean
        get() = mResume.value
        set(v) { mResume.value = v; prefs.resumeLast = v }

    private val mHorizontal = mutableStateOf(prefs.horizontal)
    var horizontal: Boolean
        get() = mHorizontal.value
        set(v) { mHorizontal.value = v; prefs.horizontal = v }

    private val mSpeed = mutableFloatStateOf(prefs.scrollSpeed)
    var scrollSpeed: Float
        get() = mSpeed.floatValue
        set(v) { mSpeed.floatValue = v; prefs.scrollSpeed = v }

    /** Not saved: auto-scroll always starts off. */
    var autoScroll by mutableStateOf(false)
}

/** Stable key for a file: name + size (works even if the URI changes). */
fun fileKey(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) "${c.getString(0)}|${c.getLong(1)}" else null
        }
    }.getOrNull() ?: uri.toString()
}

/** Loads an image from a Uri, down-sampled so it does not use too much memory. */
fun loadScaledBitmap(context: Context, uri: Uri, maxSide: Int = 1200): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

/* ---------- Headings (PDF outline) ---------- */

data class Heading(val title: String, val page: Int, val level: Int)

/** Page index of a page dictionary, found by walking up the page tree (fast even for 40,000+ pages). */
private fun pageIndexOf(page: COSDictionary): Int {
    var node: COSDictionary = page
    var index = 0
    var guard = 0
    while (guard++ < 64) {
        val parent = node.getDictionaryObject(COSName.PARENT) as? COSDictionary ?: return index
        val kids = parent.getDictionaryObject(COSName.KIDS) as? COSArray ?: return -1
        var found = false
        for (i in 0 until kids.size()) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            if (kid === node) {
                found = true
                break
            }
            index += if (COSName.PAGES == kid.getCOSName(COSName.TYPE)) kid.getInt(COSName.COUNT, 0) else 1
        }
        if (!found) return -1
        node = parent
    }
    return -1
}

private fun walkOutline(pdf: PDDocument, node: PDOutlineNode, level: Int, out: MutableList<Heading>) {
    var item: PDOutlineItem? = node.firstChild
    while (item != null) {
        val cur: PDOutlineItem = item
        val page = runCatching {
            val p = cur.findDestinationPage(pdf)
            if (p != null) pageIndexOf(p.cosObject) else -1
        }.getOrDefault(-1)
        out.add(Heading(cur.title ?: "", page, level))
        walkOutline(pdf, cur, level + 1, out)
        item = cur.nextSibling
    }
}

/**
 * Runs on a background thread. The file is copied to the cache and opened from disk
 * (so big PDFs are not loaded fully into memory). Result is cached for next time.
 */
fun loadOutline(context: Context, uri: Uri, cacheId: String): List<Heading> {
    val cacheFile = File(context.cacheDir, "headings_${cacheId.hashCode()}.tsv")
    if (cacheFile.exists()) {
        val cached = runCatching {
            cacheFile.readLines().mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) Heading(p[2], p[1].toInt(), p[0].toInt()) else null
            }
        }.getOrNull()
        if (cached != null) return cached
    }

    val tmp = File(context.cacheDir, "outline_src.pdf")
    val result = mutableListOf<Heading>()
    var ok = false
    try {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri)
        if (input != null) {
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            val pdf = PDDocument.load(tmp)
            try {
                val outline = pdf.documentCatalog.documentOutline
                if (outline != null) walkOutline(pdf, outline, 0, result)
                ok = true
            } finally {
                pdf.close()
            }
        }
    } catch (t: Throwable) {
        // ignore: no headings
    } finally {
        runCatching { tmp.delete() }
    }
    if (ok) {
        runCatching {
            cacheFile.writeText(
                result.joinToString("\n") {
                    "${it.level}\t${it.page}\t" + it.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                }
            )
        }
    }
    return result
}

/* ---------- PDF wrapper ---------- */

/** PdfRenderer allows only one open page at a time, so rendering is locked. */
class PdfDoc(context: Context, uri: Uri) {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException(tr("cannot_open_file"))
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    @Volatile private var closed = false
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap? = mutex.withLock {
        if (closed) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    renderer.openPage(index).use { page ->
                        val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
        }
    }

    /** Waits for a running render to finish, so the native renderer is never closed mid-render. */
    fun close() {
        closed = true
        runBlocking {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}


fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: (uri.lastPathSegment ?: "file.pdf")

/** The About text lives in app/src/main/assets/about.txt (edit it on GitHub). */
fun loadAbout(context: Context, code: String): String {
    fun read(name: String) = runCatching {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
    return read("about_$code.txt") ?: read("about.txt") ?: "phoethar"
}

private fun fitTo(doc: PDDocument, page: Int): PDPageFitDestination? = runCatching {
    val d = PDPageFitDestination()
    d.setPage(doc.getPage(page))
    d
}.getOrNull()

/**
 * Merges PDFs in the given order and keeps every book's headings (outline).
 * If [addTitles] is true, each book's headings are placed under a heading with the book's file name.
 * Runs on a background thread.
 */
fun mergePdfs(
    context: Context,
    sources: List<MergeSrc>,
    out: Uri,
    addTitles: Boolean,
    progress: (String) -> Unit
) {
    PDFBoxResourceLoader.init(context.applicationContext)
    val dest = PDDocument(MemoryUsageSetting.setupTempFileOnly())
    val opened = mutableListOf<PDDocument>()
    val files = mutableListOf<File>()
    val offsets = mutableListOf<Int>()
    val headingLists = mutableListOf<List<Heading>>()
    try {
        val merger = PDFMergerUtility()
        sources.forEachIndexed { i, s ->
            progress(tr("copying", i + 1, sources.size, s.name))
            val f = File(context.cacheDir, "merge_$i.pdf")
            val input = context.contentResolver.openInputStream(s.uri)
                ?: throw IllegalStateException(tr("cannot_read", s.name))
            input.use { src -> f.outputStream().use { dst -> src.copyTo(dst) } }
            files.add(f)

            progress(tr("reading", i + 1, sources.size))
            val doc = PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly())
            opened.add(doc)
            val hs = mutableListOf<Heading>()
            val outline = doc.documentCatalog.documentOutline
            if (outline != null) walkOutline(doc, outline, 0, hs)
            headingLists.add(hs)
            offsets.add(dest.numberOfPages)

            progress(tr("merging", i + 1, sources.size))
            merger.appendDocument(dest, doc)
        }

        // Rebuild the headings (outline) for the merged file
        if (addTitles || headingLists.any { it.isNotEmpty() }) {
            progress(tr("building"))
            val outline = PDDocumentOutline()
            sources.forEachIndexed { i, s ->
                val base = offsets[i]
                var root: PDOutlineNode = outline
                if (addTitles) {
                    val top = PDOutlineItem()
                    top.setTitle(s.name.removeSuffix(".pdf").removeSuffix(".PDF"))
                    val dst = fitTo(dest, base)
                    if (dst != null) top.setDestination(dst)
                    outline.addLast(top)
                    root = top
                }
                val stack = ArrayList<PDOutlineNode>()
                stack.add(root)
                for (h in headingLists[i]) {
                    val item = PDOutlineItem()
                    item.setTitle(h.title)
                    if (h.page >= 0 && base + h.page < dest.numberOfPages) {
                        val dst = fitTo(dest, base + h.page)
                        if (dst != null) item.setDestination(dst)
                    }
                    while (stack.size > h.level + 1) stack.removeAt(stack.size - 1)
                    stack[stack.size - 1].addLast(item)
                    stack.add(item)
                }
            }
            dest.documentCatalog.setDocumentOutline(outline)
        }

        progress(tr("saving"))
        val os = context.contentResolver.openOutputStream(out, "w")
            ?: throw IllegalStateException(tr("cannot_write"))
        os.use { dest.save(it) }
    } finally {
        opened.forEach { runCatching { it.close() } }
        runCatching { dest.close() }
        files.forEach { runCatching { it.delete() } }
    }
}

/* ---------- UI ---------- */

enum class Dlg { NONE, JUMP, BOOKMARKS, CONTENTS, NOTE, MERGE, ABOUT, LANG }

@Composable
fun PdfApp(initial: Uri?) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { Prefs(context) }
    val st = remember { Settings(prefs) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var uri by remember {
        mutableStateOf(initial ?: if (st.resumeLast) prefs.lastUri?.let { Uri.parse(it) } else null)
    }
    var dialog by remember { mutableStateOf(Dlg.NONE) }
    var noteTarget by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(prefs.getRecents()) }
    var menuBgUri by remember { mutableStateOf(prefs.menuBg?.let { Uri.parse(it) }) }
    var menuBgBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // merge state
    val mergeFiles = remember { mutableStateListOf<MergeSrc>() }
    var mergeTitles by remember { mutableStateOf(true) }
    var mergeBusy by remember { mutableStateOf(false) }
    var mergeStatus by remember { mutableStateOf("") }

    // settings read in composition (so the effects below re-run when they change)
    val fullScreen = st.fullScreen
    val keepOn = st.keepOn
    val autoBright = st.autoBright
    val brightness = st.brightness
    val horizontal = st.horizontal
    val volumeKeys = st.volumeKeys

    val modes = ReadMode.values()
    val night = remember(uri) { Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 19 || it < 6 } }
    val mode = if (st.autoDark && night) ReadMode.DARK else modes[st.modeIdx.coerceIn(0, modes.size - 1)]
    val filter = remember(mode) { mode.filter() }
    val dark = mode == ReadMode.DARK

    LaunchedEffect(uri) {
        st.autoScroll = false
        uri?.let { prefs.lastUri = it.toString() }
        recents = prefs.getRecents()
    }

    LaunchedEffect(menuBgUri) {
        val u = menuBgUri
        menuBgBitmap = withContext(Dispatchers.IO) {
            if (u == null) {
                BitmapFactory.decodeResource(context.resources, R.drawable.menu_bg)?.asImageBitmap()
            } else {
                loadScaledBitmap(context, u)?.asImageBitmap()
            }
        }
    }

    // ----- pickers -----
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            uri = picked
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.menuBg = picked.toString()
            menuBgUri = picked
        }
    }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        picked.forEach { u -> mergeFiles.add(MergeSrc(u, displayName(context, u))) }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) {
            val sources = mergeFiles.toList()
            val addTitles = mergeTitles
            scope.launch {
                mergeBusy = true
                mergeStatus = tr("starting")
                try {
                    withContext(Dispatchers.IO) {
                        mergePdfs(context, sources, out, addTitles) { msg -> mergeStatus = msg }
                    }
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            out,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    mergeFiles.clear()
                    mergeStatus = ""
                    dialog = Dlg.NONE
                    uri = out
                } catch (t: Throwable) {
                    mergeStatus = tr("failed", t.message ?: t.javaClass.simpleName)
                } finally {
                    mergeBusy = false
                }
            }
        }
    }

    // ----- document state -----
    val doc = remember(uri) { uri?.let { u -> runCatching { PdfDoc(context, u) }.getOrNull() } }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    val fileId = remember(uri) { uri?.let { fileKey(context, it) } }

    var scale by remember(fileId) { mutableFloatStateOf(if (fileId != null) prefs.getZoom(fileId) else 1f) }
    LaunchedEffect(scale, fileId) {
        val id = fileId
        if (id != null) {
            delay(500)
            prefs.setZoom(id, scale)
        }
    }

    val bookmarks = remember(fileId) {
        mutableStateListOf<Int>().apply { if (fileId != null) addAll(prefs.getBookmarks(fileId)) }
    }

    val listState = remember(doc) {
        val start = if (doc != null && fileId != null)
            prefs.getPage(fileId).coerceIn(0, maxOf(doc.pageCount - 1, 0))
        else 0
        LazyListState(start)
    }

    // remember last-read page (also used by Recent files)
    LaunchedEffect(listState, fileId, doc) {
        val id = fileId
        if (id != null && doc != null) {
            snapshotFlow { listState.firstVisibleItemIndex }.collectLatest {
                delay(400)
                prefs.setPage(id, it)
                prefs.updateRecentPage(id, it)
            }
        }
    }
    DisposableEffect(listState, fileId, doc) {
        onDispose {
            val id = fileId
            if (id != null && doc != null) {
                val p = listState.firstVisibleItemIndex
                prefs.setPage(id, p)
                prefs.updateRecentPage(id, p)
            }
        }
    }

    // add to Recent files when a PDF opens
    LaunchedEffect(doc, fileId) {
        val d = doc
        val id = fileId
        val u = uri
        if (d != null && id != null && u != null) {
            prefs.touchRecent(
                RecentItem(
                    id, u.toString(), id.substringBefore("|"),
                    listState.firstVisibleItemIndex, d.pageCount,
                    System.currentTimeMillis(), false
                )
            )
        }
    }

    // headings (loaded in background, cached)
    var headings by remember(uri) { mutableStateOf<List<Heading>?>(null) }
    LaunchedEffect(uri) {
        val u = uri
        if (u != null) headings = withContext(Dispatchers.IO) { loadOutline(context, u, fileId ?: u.toString()) }
    }

    // full screen, keep screen on, brightness
    SideEffect {
        val window = (context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            if (fullScreen) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window.attributes
            lp.screenBrightness =
                if (autoBright) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else brightness.coerceIn(0.02f, 1f)
            window.attributes = lp
        }
    }

    val currentPage by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    val isBookmarked = currentPage in bookmarks
    val currentHeading by remember(headings, listState) {
        derivedStateOf { headings?.lastOrNull { it.page in 0..listState.firstVisibleItemIndex } }
    }

    fun goTo(page: Int) {
        val d = doc ?: return
        scope.launch { listState.scrollToItem(page.coerceIn(0, d.pageCount - 1)) }
    }

    fun pageStep(dir: Int) {
        if (doc == null) return
        if (st.horizontal) {
            goTo(listState.firstVisibleItemIndex + dir)
        } else {
            scope.launch {
                listState.animateScrollBy(dir * listState.layoutInfo.viewportSize.height * 0.9f)
            }
        }
    }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    // volume keys turn pages
    DisposableEffect(volumeKeys, doc) {
        val act = context as? MainActivity
        val handler: (Boolean) -> Unit = { up -> pageStep(if (up) -1 else 1) }
        act?.volumeHandler = if (volumeKeys && doc != null) handler else null
        onDispose { act?.volumeHandler = null }
    }

    // auto-scroll
    LaunchedEffect(st.autoScroll, horizontal, doc, listState) {
        if (st.autoScroll && !horizontal && doc != null) {
            var last = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                try {
                    listState.scrollBy(st.scrollSpeed * dt)
                } catch (e: CancellationException) {
                    if (!isActive) throw e // a finger touched the screen: just keep going
                }
            }
        }
    }

    val dialogContext = context
    val aboutText = remember(Loc.lang) { loadAbout(dialogContext, Loc.lang.code) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        BackHandler(enabled = uri != null) { uri = null }
        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

        Box(Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    val bgImg = menuBgBitmap
                    ModalDrawerSheet(
                        Modifier.width(290.dp),
                        drawerContainerColor = if (bgImg != null) Color.Transparent else MaterialTheme.colorScheme.surface
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (bgImg != null) {
                                Image(
                                    bitmap = bgImg,
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.40f)))
                            }
                            MaterialTheme(
                                colorScheme = if (bgImg != null) darkColorScheme() else MaterialTheme.colorScheme
                            ) {
                                DrawerBody(
                                    st = st,
                                    fileName = fileId?.substringBefore("|"),
                                    hasDoc = doc != null,
                                    isBookmarked = isBookmarked,
                                    bookmarkCount = bookmarks.size,
                                    modes = modes,
                                    scale = scale,
                                    onScale = { scale = it },
                                    hasMenuBg = menuBgUri != null,
                                    onHome = { closeDrawerThen { uri = null } },
                                    onOpen = { closeDrawerThen { picker.launch(arrayOf("application/pdf")) } },
                                    onMerge = { closeDrawerThen { dialog = Dlg.MERGE } },
                                    onContents = { closeDrawerThen { dialog = Dlg.CONTENTS } },
                                    onBookmarks = { closeDrawerThen { dialog = Dlg.BOOKMARKS } },
                                    onJump = { closeDrawerThen { dialog = Dlg.JUMP } },
                                    onToggleBookmark = {
                                        val id = fileId
                                        if (id != null) {
                                            if (isBookmarked) {
                                                bookmarks.remove(currentPage)
                                                prefs.setBookmarks(id, bookmarks)
                                                prefs.setNote(id, currentPage, "")
                                            } else {
                                                noteTarget = currentPage
                                                closeDrawerThen { dialog = Dlg.NOTE }
                                            }
                                        }
                                    },
                                    onPickMenuBg = { closeDrawerThen { bgPicker.launch(arrayOf("image/*")) } },
                                    onClearMenuBg = { prefs.menuBg = null; menuBgUri = null },
                                    onLanguage = { closeDrawerThen { dialog = Dlg.LANG } },
                                    onAbout = { closeDrawerThen { dialog = Dlg.ABOUT } }
                                )
                            }
                        }
                    }
                }
            ) {
                Box(Modifier.fillMaxSize().background(mode.bg)) {
                    val d = doc
                    if (uri == null) {
                        HomeScreen(
                            recents = recents,
                            onOpenRecent = { r -> uri = Uri.parse(r.uri) },
                            onPin = { r -> prefs.togglePin(r.id); recents = prefs.getRecents() },
                            onRemove = { r -> prefs.removeRecent(r.id); recents = prefs.getRecents() },
                            onOpenFile = { picker.launch(arrayOf("application/pdf")) },
                            onMerge = { dialog = Dlg.MERGE },
                            onLanguage = { dialog = Dlg.LANG },
                            onAbout = { dialog = Dlg.ABOUT }
                        )
                    } else if (d != null) {
                        PdfViewer(d, listState, scale, { scale = it }, horizontal, filter)

                        val pct = if (d.pageCount > 0) (currentPage + 1) * 100 / d.pageCount else 0
                        Text(
                            "${currentPage + 1} / ${d.pageCount} · $pct%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable { dialog = Dlg.JUMP }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )

                        if (st.autoScroll && !horizontal) {
                            Text(
                                tr("stop"),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .clickable { st.autoScroll = false }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        Text(
                            tr("cannot_open"),
                            color = if (dark) Color.White else Color.Black,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    // top row: menu button (3 lines) + current heading with ⋮
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable { scope.launch { drawerState.open() } },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("☰", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                        val h = currentHeading
                        if (doc != null && uri != null && h != null && h.title.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Row(
                                Modifier
                                    .weight(1f, fill = false)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .clickable { dialog = Dlg.CONTENTS }
                                    .padding(start = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    h.title,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    "⋮",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Eye comfort shield: warm overlay on the whole screen. It ignores touches.
            if (st.shieldOn) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFF8F00).copy(alpha = 0.08f + st.shieldLevel * 0.32f))
                )
            }
        }

        // ---------- Dialogs ----------
        if (dialog == Dlg.JUMP && doc != null) {
            var input by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text(tr("jump")) },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(6) },
                        label = { Text("1 – ${doc.pageCount}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        input.toIntOrNull()?.let { goTo(it - 1) }
                        dialog = Dlg.NONE
                    }) { Text(tr("go")) }
                },
                dismissButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("cancel")) } }
            )
        }

        if (dialog == Dlg.NOTE) {
            var note by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text(tr("bm_title", noteTarget + 1)) },
                text = {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(80) },
                        label = { Text(tr("note_opt")) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val id = fileId
                        if (id != null) {
                            if (noteTarget !in bookmarks) bookmarks.add(noteTarget)
                            bookmarks.sort()
                            prefs.setBookmarks(id, bookmarks)
                            prefs.setNote(id, noteTarget, note.trim())
                        }
                        dialog = Dlg.NONE
                    }) { Text(tr("save")) }
                },
                dismissButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("cancel")) } }
            )
        }

        if (dialog == Dlg.BOOKMARKS) {
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text(tr("bookmarks")) },
                text = {
                    if (bookmarks.isEmpty()) {
                        Text(tr("no_bm"))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(bookmarks.toList()) { page ->
                                val note = fileId?.let { prefs.getNote(it, page) } ?: ""
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { goTo(page); dialog = Dlg.NONE },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(12.dp)) {
                                        Text(tr("page_n", page + 1))
                                        if (note.isNotBlank()) {
                                            Text(note, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    TextButton(onClick = {
                                        bookmarks.remove(page)
                                        fileId?.let {
                                            prefs.setBookmarks(it, bookmarks)
                                            prefs.setNote(it, page, "")
                                        }
                                    }) { Text("✕") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("close")) } }
            )
        }

        if (dialog == Dlg.CONTENTS) {
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text(tr("contents")) },
                text = {
                    val h = headings
                    if (h == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(tr("loading_h"))
                        }
                    } else if (h.isEmpty()) {
                        Text(tr("no_h"))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(h) { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = item.page >= 0) {
                                            goTo(item.page)
                                            dialog = Dlg.NONE
                                        }
                                        .padding(
                                            start = (item.level * 16).dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.title, Modifier.weight(1f))
                                    if (item.page >= 0) {
                                        Text(
                                            "${item.page + 1}",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("close")) } }
            )
        }

        if (dialog == Dlg.MERGE) {
            MergeDialog(
                files = mergeFiles,
                titles = mergeTitles,
                onTitles = { mergeTitles = it },
                busy = mergeBusy,
                status = mergeStatus,
                onAdd = { addPicker.launch(arrayOf("application/pdf")) },
                onMerge = { savePicker.launch("merged.pdf") },
                onClose = { dialog = Dlg.NONE }
            )
        }

        if (dialog == Dlg.LANG) {
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text("🌐  " + tr("language")) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        AppLang.values().forEach { l ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Loc.lang = l
                                        prefs.langCode = l.code
                                        dialog = Dlg.NONE
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = l == Loc.lang,
                                    onClick = {
                                        Loc.lang = l
                                        prefs.langCode = l.code
                                        dialog = Dlg.NONE
                                    }
                                )
                                Text(l.native)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("close")) } }
            )
        }

        if (dialog == Dlg.ABOUT) {
            AlertDialog(
                onDismissRequest = { dialog = Dlg.NONE },
                title = { Text(tr("about")) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(aboutText)
                        if (versionName.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(tr("version", versionName), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = Dlg.NONE }) { Text(tr("close")) } }
            )
        }
    }
}

/* ---------- Home screen (Recent files) ---------- */

@Composable
fun HomeScreen(
    recents: List<RecentItem>,
    onOpenRecent: (RecentItem) -> Unit,
    onPin: (RecentItem) -> Unit,
    onRemove: (RecentItem) -> Unit,
    onOpenFile: () -> Unit,
    onMerge: () -> Unit,
    onLanguage: () -> Unit,
    onAbout: () -> Unit
) {
    val sorted = remember(recents) {
        recents.sortedWith(compareByDescending<RecentItem> { it.pinned }.thenByDescending { it.time })
    }
    val btnColors = ButtonDefaults.buttonColors(
        containerColor = Color.White.copy(alpha = 0.25f),
        contentColor = Color.White
    )
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.home_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 72.dp, bottom = 16.dp)
        ) {
            Text("phoethar", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                tr("recent_files"),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            // translucent panel
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                if (sorted.isEmpty()) {
                    Text(
                        tr("no_recent"),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sorted, key = { it.id }) { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenRecent(r) }
                                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.name,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val pct = if (r.pages > 0) (r.page + 1) * 100 / r.pages else 0
                                    Text(
                                        tr("page_of", r.page + 1, r.pages, pct),
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = { onPin(r) }) {
                                    Text(if (r.pinned) "★" else "☆", color = Color.White)
                                }
                                TextButton(onClick = { onRemove(r) }) {
                                    Text("✕", color = Color.White)
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenFile,
                    colors = btnColors,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(tr("open_pdf"), maxLines = 1) }
                Button(
                    onClick = onMerge,
                    colors = btnColors,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(tr("merge_pdfs"), maxLines = 1) }
                Button(
                    onClick = onAbout,
                    colors = btnColors,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(tr("about"), maxLines = 1) }
                Button(
                    onClick = onLanguage,
                    colors = btnColors,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(0.45f)
                ) { Text("🌐", maxLines = 1) }
            }
        }
    }
}

/* ---------- Merge dialog ---------- */

@Composable
fun MergeDialog(
    files: SnapshotStateList<MergeSrc>,
    titles: Boolean,
    onTitles: (Boolean) -> Unit,
    busy: Boolean,
    status: String,
    onAdd: () -> Unit,
    onMerge: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(tr("merge_pdfs")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (files.isEmpty()) {
                    Text(tr("merge_hint"))
                }
                files.forEachIndexed { i, f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}. ${f.name}",
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(enabled = !busy && i > 0, onClick = {
                            val x = files.removeAt(i)
                            files.add(i - 1, x)
                        }) { Text("▲") }
                        TextButton(enabled = !busy && i < files.size - 1, onClick = {
                            val x = files.removeAt(i)
                            files.add(i + 1, x)
                        }) { Text("▼") }
                        TextButton(enabled = !busy, onClick = { files.removeAt(i) }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAdd, enabled = !busy) { Text(tr("add_pdfs")) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("merge_titles"), Modifier.weight(1f))
                    Switch(checked = titles, onCheckedChange = onTitles, enabled = !busy)
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && files.size >= 2, onClick = onMerge) { Text(tr("merge")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onClose) { Text(tr("close")) }
        }
    )
}

/* ---------- Side menu ---------- */

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun DrawerBody(
    st: Settings,
    fileName: String?,
    hasDoc: Boolean,
    isBookmarked: Boolean,
    bookmarkCount: Int,
    modes: Array<ReadMode>,
    scale: Float,
    onScale: (Float) -> Unit,
    hasMenuBg: Boolean,
    onHome: () -> Unit,
    onOpen: () -> Unit,
    onMerge: () -> Unit,
    onContents: () -> Unit,
    onBookmarks: () -> Unit,
    onJump: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPickMenuBg: () -> Unit,
    onClearMenuBg: () -> Unit,
    onLanguage: () -> Unit,
    onAbout: () -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Text(
            "phoethar",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (fileName != null) {
            Text(
                fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        DrawerItem(tr("recent_home"), onHome)
        DrawerItem(tr("open_pdf"), onOpen)
        DrawerItem(tr("merge_pdfs"), onMerge)

        if (hasDoc) {
            DrawerItem(tr("contents_h"), onContents)
            DrawerItem(tr("jump"), onJump)
            DrawerItem(
                if (isBookmarked) tr("bm_remove") else tr("bm_add"),
                onToggleBookmark
            )
            DrawerItem(tr("bookmarks_n", bookmarkCount), onBookmarks)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            var tmp by remember(scale) { mutableFloatStateOf(scale) }
            Text(
                tr("zoom", "%.1f".format(tmp)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Slider(
                value = tmp,
                onValueChange = { tmp = it },
                onValueChangeFinished = { onScale(tmp) },
                valueRange = 1f..3f,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SwitchRow(tr("horiz"), st.horizontal) { st.horizontal = it }
            SwitchRow(tr("autoscroll"), st.autoScroll, enabled = !st.horizontal) { st.autoScroll = it }
            if (st.autoScroll && !st.horizontal) {
                Text(
                    tr("speed"),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = st.scrollSpeed,
                    onValueChange = { st.scrollSpeed = it },
                    valueRange = 30f..600f,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            tr("background"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        modes.forEachIndexed { i, m ->
            Row(
                Modifier.fillMaxWidth().clickable { st.modeIdx = i }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = i == st.modeIdx, onClick = { st.modeIdx = i })
                Text(m.label)
            }
        }
        SwitchRow(tr("auto_dark"), st.autoDark) { st.autoDark = it }

        Spacer(Modifier.height(8.dp))
        SwitchRow(tr("shield"), st.shieldOn) { st.shieldOn = it }
        if (st.shieldOn) {
            Text(
                tr("warmth"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Slider(
                value = st.shieldLevel,
                onValueChange = { st.shieldLevel = it },
                valueRange = 0f..1f,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        SwitchRow(tr("sys_bright"), st.autoBright) { st.autoBright = it }
        if (!st.autoBright) {
            Text(
                tr("brightness"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Slider(
                value = st.brightness,
                onValueChange = { st.brightness = it },
                valueRange = 0.02f..1f,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        SwitchRow(tr("fullscreen"), st.fullScreen) { st.fullScreen = it }
        SwitchRow(tr("keep_on"), st.keepOn) { st.keepOn = it }
        SwitchRow(tr("vol_keys"), st.volumeKeys) { st.volumeKeys = it }
        SwitchRow(tr("resume"), st.resumeLast) { st.resumeLast = it }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        DrawerItem(tr("menu_bg"), onPickMenuBg)
        if (hasMenuBg) DrawerItem(tr("menu_bg_reset"), onClearMenuBg)
        DrawerItem("🌐  " + tr("language"), onLanguage)
        DrawerItem(tr("about"), onAbout)
    }
}

/* ---------- PDF pages ---------- */

@Composable
fun PdfViewer(
    doc: PdfDoc,
    listState: LazyListState,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    horizontal: Boolean,
    filter: ColorFilter?
) {
    if (horizontal) HorizontalPagesView(doc, listState, filter)
    else ScrollPagesView(doc, listState, scale, onScaleChange, filter)
}

@Composable
private fun ScrollPagesView(
    doc: PdfDoc,
    listState: LazyListState,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    filter: ColorFilter?
) {
    val density = LocalDensity.current
    val baseDp = LocalConfiguration.current.screenWidthDp
    val widthDp = (baseDp * scale).dp
    val widthPx = with(density) { widthDp.toPx() }.toInt().coerceAtMost(2400)

    val hScroll = rememberScrollState()
    val scaleNow by rememberUpdatedState(scale)
    val onScaleNow by rememberUpdatedState(onScaleChange)

    // Zoom from [from] to [to], keeping the spot (cx, cy) under the fingers in place.
    fun applyZoom(from: Float, to: Float, cx: Float, cy: Float) {
        val za = to / from
        hScroll.dispatchRawDelta((hScroll.value + cx) * (za - 1f))
        listState.dispatchRawDelta((listState.firstVisibleItemScrollOffset + cy) * (za - 1f))
        onScaleNow(to)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Pinch zoom: pages grow live with the fingers; the sharp re-render happens quietly afterwards.
            .pointerInput(listState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var cur = scaleNow
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                val next = (cur * z).coerceIn(1f, 3f)
                                if (next != cur) {
                                    val c = event.calculateCentroid(useCurrent = true)
                                    applyZoom(cur, next, c.x, c.y)
                                    cur = next
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Double tap: zoom in to 2x, or back to 1x
            .pointerInput(listState) {
                detectTapGestures(onDoubleTap = { off ->
                    val from = scaleNow
                    applyZoom(from, if (from > 1.05f) 1f else 2f, off.x, off.y)
                })
            }
            .horizontalScroll(hScroll)
    ) {
        LazyColumn(
            Modifier.width(widthDp).fillMaxHeight(),
            state = listState
        ) {
            items((0 until doc.pageCount).toList(), key = { it }) { index ->
                PageItem(doc, index, widthPx, filter, fit = false)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun HorizontalPagesView(doc: PdfDoc, listState: LazyListState, filter: ColorFilter?) {
    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()

    val start = listState.firstVisibleItemIndex.coerceIn(0, maxOf(doc.pageCount - 1, 0))
    val pagerState = rememberPagerState(initialPage = start) { doc.pageCount }

    // keep the page number shared with the scroll mode / jump / bookmarks
    LaunchedEffect(pagerState, listState) {
        launch {
            snapshotFlow { pagerState.currentPage }.collect {
                if (it != listState.firstVisibleItemIndex) listState.scrollToItem(it)
            }
        }
        launch {
            snapshotFlow { listState.firstVisibleItemIndex }.collect {
                if (it != pagerState.currentPage) pagerState.scrollToPage(it)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { it }
    ) { page ->
        PageItem(doc, page, widthPx, filter, fit = true)
    }
}

@Composable
fun PageItem(doc: PdfDoc, index: Int, widthPx: Int, filter: ColorFilter?, fit: Boolean) {
    // The old bitmap stays visible (just scaled) while a sharper one is rendered: no flicker.
    var bmp by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index, widthPx) {
        delay(100) // skip pages that are only flying past, or while pinching
        val r = runCatching { doc.render(index, widthPx) }.getOrNull()
        if (r != null) bmp = r
    }
    val b = bmp
    if (b != null) {
        val img = remember(b) { b.asImageBitmap() }
        Image(
            bitmap = img,
            contentDescription = tr("page_n", index + 1),
            modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
            colorFilter = filter
        )
    } else {
        Box(
            if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(0.707f),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
