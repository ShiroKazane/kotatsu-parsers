package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.EnumSet
import java.util.Locale
import java.util.UUID

@MangaSourceParser("DOUJINDESU", "Doujindesu.XXX", "id")
internal class DoujinDesuParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.DOUJINDESU, pageSize = 24) {
    private val deviceId = "kotatsu-${UUID.randomUUID()}"

    override val configKeyDomain: ConfigKey.Domain
        get() = ConfigKey.Domain("doujin.desu.xxx")

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

    override val filterCapabilities: MangaListFilterCapabilities
        get() =
            MangaListFilterCapabilities(
                isMultipleTagsSupported = true,
                isSearchSupported = true,
                isSearchWithFiltersSupported = true,
                isAuthorSearchSupported = true,
            )

    override suspend fun getFilterOptions() =
        MangaListFilterOptions(
            availableTags = fetchAvailableTags(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.DOUJINSHI),
        )

    override fun getRequestHeaders(): Headers = headers()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url =
            urlBuilder()
                .addPathSegments("api/manga")
                .apply {
                    addQueryParameter("search", filter.query ?: filter.author.orEmpty())
                    addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
                    addQueryParameter("status", filter.states.firstOrNull()?.apiValue())
                    addQueryParameter("type", filter.types.firstOrNull()?.apiValue())
                    addQueryParameter("sort", order.apiValue())
                    addQueryParameter("limit", pageSize.toString())
                    addQueryParameter("offset", ((page - 1) * pageSize).toString())
                }.build()
        return (decode(webClient.httpGet(url, headers())) as JSONArray)
            .asTypedList<JSONObject>()
            .map(::toManga)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = decode(webClient.httpGet("/api/manga/${manga.url}".toAbsoluteUrl(domain), headers())) as JSONObject
        return toManga(json).copy(
            id = manga.id,
            url = manga.url,
            publicUrl = manga.publicUrl,
            chapters = json.optJSONArray("chapters")?.mapChapters() ?: emptyList(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json =
            decode(
                webClient.httpGet("/api/chapters/${chapter.url}".toAbsoluteUrl(domain), headers()),
            ) as JSONObject
        return json.optJSONArray("content_urls")?.asTypedList<String>().orEmpty().map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> =
        (decode(webClient.httpGet("/api/genres".toAbsoluteUrl(domain), headers())) as JSONArray)
            .asTypedList<JSONObject>()
            .mapToSet { tag ->
                MangaTag(tag.optString("name"), tag.optString("slug"), source)
            }

    private fun toManga(json: JSONObject) =
        Manga(
            id = generateUid(json.getString("id")),
            title = json.optString("title"),
            altTitles = json.optString("alt_titles").split('|').mapNotNullToSet { it.trim().nullIfEmpty() },
            url = json.getString("slug"),
            publicUrl = "/manga/${json.getString("slug")}".toAbsoluteUrl(domain),
            rating = (json.optDouble("rating", RATING_UNKNOWN.toDouble()) / 10.0).toFloat(),
            contentRating = ContentRating.ADULT,
            coverUrl = json.optString("cover_url").nullIfEmpty(),
            tags =
                json
                    .optJSONArray("manga_genres")
                    ?.asTypedList<JSONObject>()
                    ?.mapNotNullToSet { item ->
                        val genre = item.optJSONObject("genres") ?: return@mapNotNullToSet null
                        MangaTag(genre.optString("name"), genre.optString("slug"), source)
                    }.orEmpty(),
            state =
                when (json.optString("status")) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
            authors = json.authorNames(),
            largeCoverUrl = json.optString("banner_url").nullIfEmpty(),
            description = cleanDescription(json.optString("description")),
            chapters = null,
            source = source,
        )

    private fun cleanDescription(value: String): String? {
        if (value.isBlank()) return null
        val document = Jsoup.parseBodyFragment(value)
        document.select("script, style, img, iframe, a[href]").remove()
        return AD_URL.replace(document.text(), "").replace(WHITESPACE, " ").trim().nullIfEmpty()
    }

    private fun JSONObject.authorNames(): Set<String> =
        listOf("author", "artist", "group", "groups", "circle")
            .flatMap { key ->
                when (val value = opt(key)) {
                    is JSONArray ->
                        (0 until value.length()).mapNotNull { index ->
                            value.opt(index).metadataName().nullIfEmpty()
                        }
                    else -> listOf(value.metadataName())
                }
            }.mapNotNullToSet { it.trim().nullIfEmpty() }

    private fun Any?.metadataName(): String =
        when (this) {
            is JSONObject -> optString("name").ifEmpty { optString("title") }
            null -> ""
            else -> toString()
        }

    private companion object {
        val AD_URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
        val WHITESPACE = Regex("\\s+")
    }

    private fun JSONArray.mapChapters(): List<MangaChapter> =
        (0 until length()).mapNotNull { index ->
            val json = optJSONObject(index) ?: return@mapNotNull null
            MangaChapter(
                id = generateUid(json.getString("id")),
                title = json.optString("title").nullIfEmpty(),
                number = json.optDouble("chapter_number", 0.0).toFloat(),
                volume = 0,
                url = json.getString("id"),
                scanlator = null,
                uploadDate = json.optString("created_at").toDateMillis(),
                branch = null,
                source = source,
            )
        }

    private fun decode(response: okhttp3.Response): Any =
        response.use {
            val body = JSONObject(it.body.string())
            val encrypted =
                body.optString("_enc_resp_").takeIf(String::isNotEmpty)
                    ?: return body
            val hour = System.currentTimeMillis() / 3_600_000L
            for (candidate in listOf(hour, hour - 1, hour + 1)) {
                try {
                    val decoded = decodePayload(encrypted, key(candidate)).trim()
                    return if (decoded.startsWith("[")) JSONArray(decoded) else JSONObject(decoded)
                } catch (_: Exception) {
                }
            }
            throw IllegalStateException("Unable to decode Doujindesu response")
        }

    private fun key(hour: Long): String {
        val value = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2_$hour"
        var hash = 0
        for (char in value) hash = (hash * 31 + char.code)
        var state =
            kotlin.math
                .abs(hash)
                .toLong()
                .takeIf { it != 0L } ?: 123456789L
        return buildString {
            repeat(32) {
                state = (state * 1_664_525L + 1_013_904_223L) and 0xffffffffL
                append((33 + (state % 93)).toInt().toChar())
            }
        }
    }

    private fun decodePayload(
        value: String,
        secret: String,
    ): String {
        val bytes = value.chunked(2).map { it.toInt(16) }
        var state = 42
        val decoded =
            ByteArray(bytes.size) { index ->
                val result = bytes[index] xor secret[index % secret.length].code xor (index * 13) xor state
                state = (state + bytes[index]) and 255
                result.toByte()
            }
        return java.net.URLDecoder.decode(decoded.toString(Charsets.UTF_8), Charsets.UTF_8.name())
    }

    private fun headers() =
        Headers
            .Builder()
            .add("X-App-Secret", "dfdf72051dbfdc7d76889ebd31324e74")
            .add("x-app-secret", "dfdf72051dbfdc7d76889ebd31324e74")
            .add("X-Device-ID", deviceId)
            .add("x-device-id", deviceId)
            .add("X-Device-Name", "Kotatsu")
            .add("Referer", "https://$domain/")
            .build()

    private fun String?.toDateMillis(): Long =
        runCatching {
            java.text
                .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
                .parse(this.orEmpty())
                ?.time ?: 0L
        }.getOrDefault(0L)

    private fun ContentType.apiValue() =
        when (this) {
            ContentType.DOUJINSHI -> "doujinshi"
            ContentType.MANHWA -> "manhwa"
            else -> "manga"
        }

    private fun MangaState.apiValue() = if (this == MangaState.FINISHED) "completed" else "ongoing"

    private fun SortOrder.apiValue() =
        when (this) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.UPDATED -> "latest_chapter"
            else -> "newest"
        }
}
