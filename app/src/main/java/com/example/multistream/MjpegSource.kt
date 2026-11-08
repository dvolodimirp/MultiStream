package com.example.multistream

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Власний MJPEG-клієнт (multipart/x-mixed-replace), наприклад mjpg-streamer:
 * читає потік у фоновому потоці, декодує кадри й віддає останній кадр та статус у Compose.
 * Також підтримує одиночні JPEG (?action=snapshot) — тоді кадр оновлюється опитуванням.
 */
class MjpegSource(private val item: StreamItem) {

    /** Останній кадр (null, доки нічого не прийшло). */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Порожній рядок — усе гаразд, інакше текст стану/помилки. */
    var status by mutableStateOf("Підключення…")
        private set

    @Volatile private var generation = 0
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        val gen = ++generation
        status = "Підключення…"
        thread = Thread({ loop(gen) }, "mjpeg-${item.id}").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        generation++
        conn?.disconnect()
        thread = null
    }

    private fun loop(gen: Int) {
        while (gen == generation) {
            var polled = false
            try {
                polled = streamOnce(gen)
                if (gen == generation && !polled) status = "Потік завершився, перепідключення…"
            } catch (e: Exception) {
                if (gen != generation) return
                status = describe(e)
            }
            val waitMs = if (polled) 500 else 3000
            var waited = 0
            while (gen == generation && waited < waitMs) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
                waited += 100
            }
        }
    }

    /** Повертає true, якщо це був одиночний знімок (треба опитувати), false — якщо потік закінчився. */
    private fun streamOnce(gen: Int): Boolean {
        val (user, pass) = item.credentials()
        val c = (URL(cleanUrl(item.url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MultiStream/1.0")
            setRequestProperty("Accept", "*/*")
            if (user.isNotEmpty()) {
                val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        conn = c
        try {
            val code = c.responseCode
            if (code == 401) throw IOException("HTTP 401: невірний або потрібен логін/пароль (підтримується Basic)")
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = (c.contentType ?: "").lowercase()
            if (frame == null) status = "З'єднано (${c.contentType}), чекаю кадр…"
            val input = BufferedInputStream(c.inputStream, 64 * 1024)
            return when {
                "multipart" in type -> {
                    readMultipart(input, gen)
                    false
                }
                type.startsWith("image/") -> {
                    publish(input.readBytes())
                    true
                }
                else -> throw IOException("Не MJPEG: тип відповіді «${c.contentType}»")
            }
        } finally {
            c.disconnect()
            if (conn === c) conn = null
        }
    }

    private fun readMultipart(input: InputStream, gen: Int) {
        while (gen == generation) {
            // Початок частини: рядок-межа "--boundary"
            var line = readLine(input) ?: return
            while (!line.startsWith("--")) line = readLine(input) ?: return
            // Заголовки частини до порожнього рядка
            var len = -1
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
                if (h.startsWith("content-length", ignoreCase = true)) {
                    len = h.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            val jpeg = if (len in 1..MAX_FRAME) readExactly(input, len) else readUntilEoi(input)
            if (gen != generation) return
            publish(jpeg)
        }
    }

    private fun publish(bytes: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) {
            status = "Не вдалось декодувати кадр (${bytes.size} байт)"
            return
        }
        frame = bmp.asImageBitmap()
        if (status.isNotEmpty()) status = ""
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 4096) sb.append(b.toChar())
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    /** Запасний варіант без Content-Length: читаємо до маркера кінця JPEG (FF D9). */
    private fun readUntilEoi(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b < 0) throw EOFException()
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
            if (out.size() > MAX_FRAME) throw IOException("Кадр завеликий")
            prev = b
        }
    }

    /** Прибираємо user:pass@ з адреси — логін іде заголовком Authorization. */
    private fun cleanUrl(url: String): String {
        val u = Uri.parse(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        val path = u.encodedPath?.ifEmpty { "/" } ?: "/"
        return "${u.scheme}://${u.host}$port$path" +
            (u.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Таймаут: камера не відповідає"
        is UnknownHostException -> "Невідомий хост: ${e.message}"
        is ConnectException -> "Немає з'єднання: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }.take(140)

    private companion object {
        const val MAX_FRAME = 10_000_000
    }
}
