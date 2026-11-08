package com.example.multistream

import android.net.Uri

/** Перетворює посилання YouTube / Twitch на вбудований плеєр для WebView. */
object Embeds {
    const val BASE_URL = "https://multistream.app/"
    private const val PARENT = "multistream.app"

    fun isWeb(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("twitch.tv")
    }

    /** Типові адреси MJPEG-джерел (mjpg-streamer, motion, IP-камери). */
    fun looksMjpeg(url: String): Boolean {
        val l = url.lowercase()
        return "action=stream" in l || "action=snapshot" in l || "mjpg" in l || "mjpeg" in l
    }

    fun html(url: String): String {
        val src = embedSrc(url)
            ?: return page(
                "<p style=\"color:#fff;font:14px sans-serif;padding:12px\">" +
                    "Не вдалося розпізнати посилання. YouTube: watch?v=…, youtu.be/…, /live/… " +
                    "або /channel/UC…. Twitch: twitch.tv/назва_каналу.</p>",
            )
        return page(
            "<iframe src=\"$src\" style=\"position:absolute;top:0;left:0;width:100%;height:100%;border:0\" " +
                "allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen></iframe>",
        )
    }

    private fun page(body: String) =
        "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
            "<body style=\"margin:0;background:#000\">$body</body></html>"

    private fun embedSrc(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return null
        val segs = uri.pathSegments
        return when {
            host == "youtu.be" -> segs.firstOrNull()?.let(::youtube)
            host.endsWith("youtube.com") -> when (segs.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")?.let(::youtube)
                "live", "embed", "shorts" -> segs.getOrNull(1)?.let(::youtube)
                "channel" -> segs.getOrNull(1)?.let {
                    "https://www.youtube.com/embed/live_stream?channel=${Uri.encode(it)}" +
                        "&autoplay=1&mute=1&playsinline=1&rel=0"
                }
                else -> null
            }
            host.endsWith("twitch.tv") -> when {
                segs.firstOrNull() == "videos" -> segs.getOrNull(1)?.let {
                    "https://player.twitch.tv/?video=v${Uri.encode(it)}&parent=$PARENT&autoplay=true&muted=true"
                }
                segs.isNotEmpty() ->
                    "https://player.twitch.tv/?channel=${Uri.encode(segs[0])}&parent=$PARENT&autoplay=true&muted=true"
                else -> null
            }
            else -> null
        }
    }

    private fun youtube(id: String) =
        "https://www.youtube.com/embed/${Uri.encode(id)}?autoplay=1&mute=1&playsinline=1&rel=0"
}
