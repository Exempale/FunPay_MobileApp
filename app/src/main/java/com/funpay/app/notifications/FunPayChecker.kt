package com.funpay.app.notifications

import android.content.Context
import android.util.Log
import com.funpay.app.utils.CookieHelper
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Снимок одного диалога из левого списка чатов FunPay.
 *
 * Разметка FunPay (проверено по открытым парсерам funpayparsers / FunPayAPI):
 * ```
 * <a class="contact-item unread" data-id="123" data-node-msg="4567" data-user-msg="4560" href="/chat/?node=123">
 *     <div class="media-user-name">Покупатель</div>
 *     <div class="contact-item-message">Привет!</div>
 *     <div class="contact-item-time">12:30</div>
 * </a>
 * ```
 */
data class ChatSnapshot(
    val id: Long,
    val name: String,
    val preview: String,
    val lastMessageId: Long,
    val lastReadMessageId: Long,
    val unread: Boolean,
)

/**
 * Результат одной проверки.
 *
 * @param totalUnread счётчик из шапки сайта (`a.menu-item-chat > span.badge`)
 * @param chats список диалогов со страницы /chat/
 * @param authorized удалось ли увидеть авторизованную страницу
 * @param parsedChatList удалось ли распознать разметку списка чатов
 */
data class ChatCheckResult(
    val totalUnread: Int,
    val chats: List<ChatSnapshot>,
    val authorized: Boolean,
    val parsedChatList: Boolean,
)

/**
 * FunPayChecker — реальная проверка новых сообщений.
 *
 * У FunPay нет публичного push-API, поэтому используется тот же подход,
 * что и в популярных ботах: HTTP-запрос страницы /chat/ с cookie сессии
 * и разбор счётчика непрочитанных сообщений + списка диалогов.
 */
object FunPayChecker {

    private const val TAG = "FunPayChecker"

    private const val CHAT_URL = "https://funpay.com/chat/"
    private const val TIMEOUT_MS = 15_000

    /**
     * UA как у мобильного Chrome: если отправлять собственный UA,
     * FunPay может отдать урезанную/неожиданную разметку.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val TAG_CONTACT_ITEM = Regex(
        "<a\\b[^>]*class=\"[^\"]*\\bcontact-item\\b[^\"]*\"[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    private val TAG_MENU_CHAT = Regex(
        "<a\\b[^>]*class=\"[^\"]*\\bmenu-item-chat\\b[^\"]*\"[^>]*>(.{0,800}?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val TAG_BADGE_NUMBER = Regex(
        "<span[^>]*class=\"[^\"]*\\bbadge\\b[^\"]*\"[^>]*>\\s*(\\d[\\d\\s]*)\\s*</span>",
        RegexOption.IGNORE_CASE,
    )

    private val TAG_STRIP = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Выполняет проверку. Возвращает null только при сетевой ошибке
     * (нет связи, таймаут, 5xx) — чтобы вызывающая сторона могла
     * отличить «не смогли проверить» от «новых сообщений нет».
     */
    fun fetch(context: Context): ChatCheckResult? {
        val cookies = CookieHelper.savedCookies(context).orEmpty()

        // Одна повторная попытка: короткий сбой сети не должен приводить
        // к пропущенному сообщению.
        val body = httpGet(CHAT_URL, cookies)
            ?: httpGet(CHAT_URL, cookies)
            ?: return null

        return parse(body)
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun httpGet(url: String, cookies: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Cache-Control", "no-cache")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies)
            }
            connection = conn

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code for $url")
                return null
            }

            val encoding = conn.contentEncoding.orEmpty()
            val stream = conn.inputStream
            if (encoding.contains("gzip", ignoreCase = true)) {
                GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    // ─── Парсинг ─────────────────────────────────────────────────────────────

    /** Разбирает HTML-страницу FunPay. Вынесено отдельно для тестируемости. */
    fun parse(body: String): ChatCheckResult {
        val authorized = body.contains("user-link-name") || body.contains("menu-item-logout")
        val chats = parseChats(body)
        val parsedChatList = body.contains("contact-list") || body.contains("contact-item")

        // Счётчик в шапке — самый надёжный источник, но он может отставать
        // от списка чатов, поэтому берём максимум из двух значений.
        val headerUnread = parseHeaderUnread(body)
        val unreadChats = chats.count { it.unread }
        val totalUnread = if (authorized) maxOf(headerUnread, unreadChats) else 0

        return ChatCheckResult(
            totalUnread = totalUnread,
            chats = chats,
            authorized = authorized,
            parsedChatList = parsedChatList,
        )
    }

    /** Счётчик непрочитанных в шапке сайта (0 — бейджа нет). */
    private fun parseHeaderUnread(body: String): Int {
        val chatLink = TAG_MENU_CHAT.find(body) ?: return 0
        val badge = TAG_BADGE_NUMBER.find(chatLink.groupValues[1]) ?: return 0
        return badge.groupValues[1].replace(" ", "").toIntOrNull() ?: 0
    }

    private fun parseChats(body: String): List<ChatSnapshot> {
        val tags = TAG_CONTACT_ITEM.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()

        val result = ArrayList<ChatSnapshot>(tags.size)
        tags.forEachIndexed { index, match ->
            val tag = match.value
            val id = attr(tag, "data-id")?.toLongOrNull() ?: return@forEachIndexed

            val start = match.range.last + 1
            val end = if (index + 1 < tags.size) {
                tags[index + 1].range.first
            } else {
                minOf(body.length, start + 4000)
            }
            if (end <= start) return@forEachIndexed
            val chunk = body.substring(start, end)

            val classes = attr(tag, "class").orEmpty()
            val unread = classes.split(WHITESPACE).any { it.equals("unread", ignoreCase = true) }

            val name = textOf(chunk, "media-user-name")
                .ifEmpty { textOf(chunk, "contact-item-name") }
                .ifEmpty { "Чат #$id" }

            result += ChatSnapshot(
                id = id,
                name = name,
                preview = textOf(chunk, "contact-item-message"),
                lastMessageId = attr(tag, "data-node-msg")?.toLongOrNull() ?: 0L,
                lastReadMessageId = attr(tag, "data-user-msg")?.toLongOrNull() ?: 0L,
                unread = unread,
            )
        }
        return result
    }

    /** Значение атрибута из строки тега. */
    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.getOrNull(1)

    /** Текст первого элемента с указанным классом внутри HTML-фрагмента. */
    private fun textOf(chunk: String, className: String): String {
        val regex = Regex(
            "<([a-zA-Z0-9]+)[^>]*class=\"[^\"]*\\b$className\\b[^\"]*\"[^>]*>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val raw = regex.find(chunk)?.groupValues?.getOrNull(2) ?: return ""
        return normalize(raw)
    }

    /** Убирает теги, раскрывает HTML-сущности и нормализует пробелы. */
    private fun normalize(html: String): String {
        val noTags = TAG_STRIP.replace(html, " ")
        val decoded = decodeEntities(noTags)
        return SPACES.replace(decoded, " ").trim().take(160)
    }

    private fun decodeEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")

        // Числовые сущности: &#123; и &#x1F600;
        val numeric = Regex("&#(x?)([0-9a-fA-F]+);")
        out = numeric.replace(out) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val code = match.groupValues[2].toIntOrNull(radix)
            if (code != null && Character.isValidCodePoint(code) && code > 0) {
                String(Character.toChars(code))
            } else {
                match.value
            }
        }
        return out
    }
}
