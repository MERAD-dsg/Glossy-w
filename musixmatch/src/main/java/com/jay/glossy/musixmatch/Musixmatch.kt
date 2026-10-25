package com.jay.glossy.musixmatch

import android.util.Log
import com.jay.glossy.musixmatch.models.MessageHeader
import com.jay.glossy.musixmatch.models.RichSyncEntry
import com.jay.glossy.musixmatch.models.RichSyncResponse
import com.jay.glossy.musixmatch.models.RichSyncResponseBody
import com.jay.glossy.musixmatch.models.SearchTrackResponse
import com.jay.glossy.musixmatch.models.SearchTrackResponseBody
import com.jay.glossy.musixmatch.models.SubtitleEntry
import com.jay.glossy.musixmatch.models.TokenResponse
import com.jay.glossy.musixmatch.models.TokenResponseBody
import com.jay.glossy.musixmatch.models.TrackLyricsResponse
import com.jay.glossy.musixmatch.models.TrackLyricsResponseBody
import com.jay.glossy.musixmatch.models.TrackContainer
import com.jay.glossy.musixmatch.models.TrackSubtitleResponse
import com.jay.glossy.musixmatch.models.TrackSubtitleResponseBody
import com.jay.glossy.musixmatch.models.Track
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Client for the Musixmatch mobile API.
 *
 * Every request is signed with a secret scraped from the web app, and needs a
 * user token minted with that secret. Both are cached, and only a rejection from
 * the server (401/402) throws them away. Any other failure, such as a song that
 * has no lyrics, leaves them alone: wiping them on every error made each later
 * lookup scrape the web app again, which overran the provider's time budget.
 */
object Musixmatch {
    private const val TAG = "Musixmatch"
    private const val BASE_URL = "https://apic.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mobile-app-v1.0"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Used only when the secret cannot be scraped from the web app. */
    private const val FALLBACK_SECRET = "b3dc8788299f5806a70a6a20a0cb0ffc"
    private const val SECRET_TTL_MS = 6L * 60 * 60 * 1000
    private const val TOKEN_TTL_MS = 60L * 60 * 1000
    private const val DURATION_TOLERANCE_SECONDS = 7

    private const val STATUS_OK = 200
    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_USAGE_LIMIT = 402

    private val appScriptRegex = Regex("src=\"([^\"]*/_next/static/chunks/pages/_app-[^\"]+\\.js)\"")
    private val secretRegex = Regex("from\\(\\s*\"(.*?)\"\\s*\\.split")
    private val commercialDisclaimerRegex = Regex("lyrics\\s+is\\s+not\\s+for\\s+commercial\\s+use", RegexOption.IGNORE_CASE)
    private val bracketedTextRegex = Regex("\\(.*?\\)|\\[.*?\\]")
    private val nonAlphanumericRegex = Regex("[^a-z0-9 ]")
    private val whitespaceRegex = Regex("\\s+")

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(jsonCodec)
                json(jsonCodec, ContentType.Text.Html)
                json(jsonCodec, ContentType.Text.Plain)
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
        }
    }

    private class Timed(val value: String, val fetchedAtMs: Long) {
        fun isFresh(ttlMs: Long): Boolean = System.currentTimeMillis() - fetchedAtMs < ttlMs
    }

    private class Session(val secret: String, val token: String)

    private class AuthExpiredException(message: String) : Exception(message)

    private val secretSlot = AtomicReference<Timed?>(null)
    private val tokenSlot = AtomicReference<Timed?>(null)
    private val credentialsLock = Mutex()
    private val processGuid = UUID.randomUUID().toString()

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> =
        try {
            Result.success(
                withSession { session ->
                    val trackId = searchTrackId(session, title, artist, duration)
                    coroutineScope {
                        val richSync = async { attempt { richSyncLrc(session, trackId) } }
                        val subtitle = async { attempt { subtitleLrc(session, trackId) } }
                        val plain = async { attempt { plainLyrics(session, trackId) } }
                        // Rich sync carries word timings, subtitles carry line timings, and plain text is the last resort.
                        richSync.await() ?: subtitle.await() ?: plain.await()
                            ?: throw IllegalStateException("No Musixmatch lyrics for track $trackId")
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("getLyrics failed for $artist - $title: ${e.message}")
            Result.failure(e)
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val found: List<String> =
            try {
                withSession { session ->
                    val trackId = searchTrackId(session, title, artist, duration)
                    coroutineScope {
                        listOf(
                            async { attempt { richSyncLrc(session, trackId) } },
                            async { attempt { subtitleLrc(session, trackId) } },
                            async { attempt { plainLyrics(session, trackId) } },
                        ).map { it.await() }
                    }.filterNotNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("getAllLyrics failed for $artist - $title: ${e.message}")
                emptyList()
            }
        // Emitted only once every format has been fetched, so a retry after an
        // expired token can never deliver the same answer twice.
        found.forEach(callback)
    }

    private suspend fun <T> withSession(block: suspend (Session) -> T): T {
        try {
            return block(session())
        } catch (e: AuthExpiredException) {
            log("Credentials rejected, refreshing once: ${e.message}")
            secretSlot.set(null)
            tokenSlot.set(null)
        }
        return block(session())
    }

    private suspend fun session(): Session {
        val secret = cached(secretSlot, SECRET_TTL_MS) { fetchSecret() }
        val token = cached(tokenSlot, TOKEN_TTL_MS) { requestUserToken(secret) }
        return Session(secret, token)
    }

    /**
     * Returns the cached value while it is fresh. Otherwise one caller fetches it
     * under the lock, and the others wait for that result instead of repeating
     * the same network round trips.
     */
    private suspend fun cached(
        slot: AtomicReference<Timed?>,
        ttlMs: Long,
        load: suspend () -> String,
    ): String {
        slot.get()?.takeIf { it.isFresh(ttlMs) }?.let { return it.value }
        return credentialsLock.withLock {
            slot.get()?.takeIf { it.isFresh(ttlMs) }?.value
                ?: load().also { slot.set(Timed(it, System.currentTimeMillis())) }
        }
    }

    private suspend fun fetchSecret(): String =
        try {
            val searchPage = client.get("https://www.musixmatch.com/search") {
                header("User-Agent", USER_AGENT)
                header("Cookie", "mxm_bab=AB")
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Sec-Fetch-Dest", "document")
                header("Sec-Fetch-Mode", "navigate")
                header("Sec-Fetch-Site", "none")
                header("Upgrade-Insecure-Requests", "1")
            }.body<String>()

            val appScriptUrl = appScriptRegex.find(searchPage)?.groupValues?.get(1)
                ?: throw IllegalStateException("Musixmatch app script not found")
            val appScript = client.get(appScriptUrl) {
                header("User-Agent", USER_AGENT)
                header("Accept", "*/*")
                header("Accept-Language", "en-US,en;q=0.9")
                header("Sec-Fetch-Dest", "script")
                header("Sec-Fetch-Mode", "no-cors")
                header("Sec-Fetch-Site", "cross-site")
            }.body<String>()

            // The secret is stored reversed and base64-encoded inside the script.
            val encoded = secretRegex.find(appScript)?.groupValues?.get(1)
                ?: throw IllegalStateException("Musixmatch secret not found in app script")
            String(Base64.getDecoder().decode(encoded.reversed()), StandardCharsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Secret scrape failed, using fallback: ${e.message}")
            FALLBACK_SECRET
        }

    private suspend fun requestUserToken(secret: String): String {
        val response: TokenResponse = fetch(
            sign("${BASE_URL}token.get?app_id=$APP_ID&guid=$processGuid&format=json", secret),
        )
        response.message.header.requireOk("token.get")
        return response.message.body.decodeBody<TokenResponseBody>()?.userToken
            ?: throw IllegalStateException("Musixmatch returned no user token")
    }

    private suspend fun searchTrackId(
        session: Session,
        title: String,
        artist: String,
        duration: Int,
    ): Long {
        val query = "${BASE_URL}track.search?app_id=$APP_ID&format=json" +
            "&q_track=${encode(title)}&q_artist=${encode(artist)}" +
            "&f_has_lyrics=true&page_size=10&usertoken=${session.token}"
        val response: SearchTrackResponse = fetch(sign(query, session.secret))
        response.message.header.requireOk("track.search")
        val tracks = response.message.body.decodeBody<SearchTrackResponseBody>()?.trackList.orEmpty()
        if (tracks.isEmpty()) throw IllegalStateException("No Musixmatch track for $artist - $title")
        return findBestMatchingTrack(tracks, title, artist, duration).trackId
    }

    private suspend fun richSyncLrc(session: Session, trackId: Long): String {
        val response: RichSyncResponse = fetch(
            sign("${BASE_URL}track.richsync.get?app_id=$APP_ID&format=json&track_id=$trackId&usertoken=${session.token}", session.secret),
        )
        response.message.header.requireOk("track.richsync.get")
        val richSyncBody = response.message.body.decodeBody<RichSyncResponseBody>()?.richsync?.richsyncBody
        val entries = jsonCodec.decodeFromString<List<RichSyncEntry>>(
            richSyncBody?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No rich sync for $trackId"),
        )
        return convertRichSyncToLrc(entries).requireText("rich sync for $trackId")
    }

    private suspend fun subtitleLrc(session: Session, trackId: Long): String {
        val response: TrackSubtitleResponse = fetch(
            sign("${BASE_URL}track.subtitle.get?app_id=$APP_ID&format=json&track_id=$trackId&usertoken=${session.token}", session.secret),
        )
        response.message.header.requireOk("track.subtitle.get")
        val subtitleBody = response.message.body.decodeBody<TrackSubtitleResponseBody>()?.subtitle?.subtitleBody
        return convertSubtitleJsonToLrc(
            subtitleBody?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No subtitles for $trackId"),
        ).requireText("subtitles for $trackId")
    }

    private suspend fun plainLyrics(session: Session, trackId: Long): String {
        val response: TrackLyricsResponse = fetch(
            sign("${BASE_URL}track.lyrics.get?app_id=$APP_ID&format=json&track_id=$trackId&usertoken=${session.token}", session.secret),
        )
        response.message.header.requireOk("track.lyrics.get")
        val lyricsBody = response.message.body.decodeBody<TrackLyricsResponseBody>()?.lyrics?.lyricsBody
        return removeDisclaimer(lyricsBody.orEmpty()).requireText("plain lyrics for $trackId")
    }

    /**
     * One GET against the API. A rejected token or usage limit becomes
     * [AuthExpiredException], so the caller knows a refresh is worth trying.
     */
    private suspend inline fun <reified T> fetch(signedUrl: String): T =
        try {
            client.get(signedUrl) {
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "en-US,en;q=0.9")
            }.body<T>()
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            if (status == STATUS_UNAUTHORIZED || status == STATUS_USAGE_LIMIT) {
                throw AuthExpiredException("HTTP $status")
            }
            throw e
        }

    private fun MessageHeader.requireOk(request: String) {
        when (statusCode) {
            STATUS_OK -> Unit
            STATUS_UNAUTHORIZED, STATUS_USAGE_LIMIT -> throw AuthExpiredException("$request returned $statusCode")
            else -> throw IllegalStateException("$request returned status $statusCode")
        }
    }

    private inline fun <reified T> JsonElement?.decodeBody(): T? =
        if (this is JsonObject) jsonCodec.decodeFromJsonElement<T>(this) else null

    /** A failed format must not hide the others, but an expired token must reach [withSession]. */
    private suspend fun attempt(block: suspend () -> String): String? =
        try {
            block()
        } catch (e: AuthExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Format unavailable: ${e.message}")
            null
        }

    private fun String.requireText(what: String): String =
        takeIf { it.isNotBlank() } ?: throw IllegalStateException("Empty $what")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun sign(url: String, secret: String): String {
        val normalizedUrl = url.replace("%20", "+").replace(" ", "+")
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(
            mac.doFinal((normalizedUrl + dateStamp).toByteArray(StandardCharsets.UTF_8)),
        )
        return "$normalizedUrl&signature=${URLEncoder.encode(signature, StandardCharsets.UTF_8.name())}&signature_protocol=sha256"
    }

    private fun findBestMatchingTrack(
        trackList: List<TrackContainer>,
        title: String,
        artist: String,
        duration: Int,
    ): Track {
        val queryTitle = cleanText(title)
        val queryArtist = cleanText(artist)
        val tracks = trackList.map { it.track }

        val withMatchingDuration = if (duration > 0) {
            tracks.filter { track ->
                val length = track.trackLength ?: 0
                length > 0 && abs(length - duration) <= DURATION_TOLERANCE_SECONDS
            }
        } else {
            emptyList()
        }
        val candidates = withMatchingDuration.ifEmpty { tracks }

        return candidates.minByOrNull { track ->
            val titleScore = when (val candidate = cleanText(track.trackName)) {
                queryTitle -> 0
                else -> if (candidate.contains(queryTitle) || queryTitle.contains(candidate)) 1 else 2
            }
            val artistCandidate = cleanText(track.artistName)
            val artistScore = when (artistCandidate) {
                queryArtist -> 0
                else -> if (artistCandidate.contains(queryArtist) || queryArtist.contains(artistCandidate)) 1 else 2
            }
            val length = track.trackLength ?: 0
            val durationDelta = if (duration > 0 && length > 0) abs(length - duration) else 0
            (titleScore * 1000) + (artistScore * 100) + durationDelta
        } ?: tracks.first()
    }

    /** Lowercase, ASCII letters and digits only, so titles compare regardless of punctuation or brackets. */
    internal fun cleanText(text: String): String =
        text.replace(",", " ")
            .replace("&", " ")
            .replace(bracketedTextRegex, "")
            .lowercase(Locale.US)
            .replace(nonAlphanumericRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()

    private fun removeDisclaimer(lyrics: String): String =
        lyrics.lines()
            .filterNot { commercialDisclaimerRegex.containsMatchIn(it) }
            .joinToString("\n")
            .trim()

    internal fun convertRichSyncToLrc(entries: List<RichSyncEntry>): String {
        val sb = StringBuilder()
        for (entry in entries) {
            val lineText = entry.x.trim()
            if (lineText.isEmpty()) continue

            // Lines wrapped in parentheses are background vocals, marked v2 so the lyric view styles them as such.
            val isBackground = lineText.startsWith("(") && lineText.endsWith(")")
            val agentLabel = if (isBackground) "v2:" else "v1:"

            sb.append(formatTime((entry.ts * 1000).toLong(), isSyllable = false))
                .append(agentLabel)
                .append(" ")

            for (word in entry.l) {
                if (word.c.isBlank()) {
                    // Keep the separator between words, without a timestamp.
                    sb.append(word.c)
                    continue
                }
                var wordText = word.c
                if (isBackground) wordText = wordText.replace("(", "").replace(")", "")
                if (wordText.isNotBlank()) {
                    val wordTimeMs = ((entry.ts + word.o) * 1000).toLong()
                    sb.append(formatTime(wordTimeMs, isSyllable = true)).append(wordText)
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    internal fun convertSubtitleJsonToLrc(body: String): String {
        val entries = try {
            jsonCodec.decodeFromString<List<SubtitleEntry>>(body)
        } catch (e: Exception) {
            // Not JSON: the body is already timed text, so it is passed through as it is.
            return removeDisclaimer(body)
        }
        val sb = StringBuilder()
        for (entry in entries) {
            val lineText = entry.text.trim()
            if (commercialDisclaimerRegex.containsMatchIn(lineText)) continue
            val isBackground = lineText.startsWith("(") && lineText.endsWith(")")
            val agentLabel = if (isBackground) "v2:" else "v1:"
            sb.append(formatTime((entry.time.total * 1000).toLong(), isSyllable = false))
                .append(agentLabel)
                .append(" ")
                .append(if (isBackground) lineText.removeSurrounding("(", ")").trim() else entry.text)
                .append("\n")
        }
        return sb.toString()
    }

    private fun formatTime(timeMs: Long, isSyllable: Boolean): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val minutes = (safeMs / 1000) / 60
        val seconds = (safeMs / 1000) % 60
        val millis = safeMs % 1000
        val open = if (isSyllable) "<" else "["
        val close = if (isSyllable) ">" else "]"
        return String.format(Locale.US, "%s%02d:%02d.%03d%s", open, minutes, seconds, millis, close)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
