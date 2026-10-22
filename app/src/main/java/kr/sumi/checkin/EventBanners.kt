package kr.sumi.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 이벤트 배너 그림. 호요랩 주소에서 그때그때 받아 보여 준다(앱에 넣지 않는다).
 * 받은 파일은 캐시 폴더에 두고(3주 안 쓰면 지운다), 해상도는 줄이지 않는다.
 */
object EventBanners {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val KEEP_MS = 21 * 24 * 60 * 60_000L

    /** view에 url 그림을 넣는다. 못 받으면 그대로 둔다(자리 배경이 보인다). */
    fun load(context: Context, url: String, view: ImageView) {
        if (url.isEmpty()) return
        view.tag = url
        memory.get(url)?.let { view.setImageBitmap(it); return }
        val app = context.applicationContext
        pool.execute {
            val bitmap = runCatching { decode(fetch(app, url)) }.getOrNull() ?: return@execute
            memory.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }

    /**
     * 목록을 그리기 전에 배너를 미리 받아 둔다(글자만 먼저 뜨고 그림이 나중에 튀어나오지 않게).
     * 다 받았거나 timeoutMs가 지나면 done을 메인 스레드에서 한 번 부른다. 늦은 그림은 계속 받아 두었다가 자리에 들어간다.
     */
    fun prefetch(context: Context, urls: List<String>, timeoutMs: Long, done: () -> Unit) {
        val todo = urls.filter { it.isNotEmpty() && memory.get(it) == null }.distinct()
        if (todo.isEmpty()) { done(); return }
        val app = context.applicationContext
        var called = false
        val finish = Runnable { if (!called) { called = true; done() } }
        var left = todo.size
        main.postDelayed(finish, timeoutMs)
        for (url in todo) pool.execute {
            runCatching { decode(fetch(app, url)) }.getOrNull()?.let { memory.put(url, it) }
            main.post { if (--left == 0) { main.removeCallbacks(finish); finish.run() } }
        }
    }

    private fun fetch(context: Context, url: String): File {
        val dir = File(context.cacheDir, "banners").apply { mkdirs() }
        val file = File(dir, url.hashCode().toUInt().toString(16))
        if (file.length() > 0) return file.also { it.setLastModified(System.currentTimeMillis()) }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val tmp = File(dir, file.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            compact(tmp)
            tmp.renameTo(file)
            return file
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 받은 그림을 같은 해상도 그대로 JPEG(품질 92)로 다시 저장해 본다. 원본보다 20% 넘게 작아질 때만 바꾼다.
     * 해상도는 줄이지 않는다(폰마다 화면이 달라서). 사진 같은 PNG 배너는 크게 줄고, 이미 JPEG인 배너는 원본을 그대로 둔다.
     */
    private fun compact(file: File) {
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return
        val out = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) return
        if (out.size() < file.length() * 0.8) file.writeBytes(out.toByteArray())
    }

    internal fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)

}
