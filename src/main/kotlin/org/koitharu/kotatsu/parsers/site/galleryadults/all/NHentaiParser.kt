package org.koitharu.kotatsu.parsers.site.galleryadults.all

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.util.EnumSet
import java.util.Locale

/**
 * nhentai is parsed through its documented JSON API (`/api/v2`) rather than the HTML:
 * the site is a Svelte app whose markup changes often, and the legacy `/api/gallery/<id>`
 * endpoint now answers `403 Use new API`.
 */
@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.NHENTAI, PAGE_SIZE) {
    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.POPULARITY_TODAY,
            SortOrder.POPULARITY_WEEK,
            SortOrder.POPULARITY_MONTH,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() =
            MangaListFilterCapabilities(
                isSearchSupported = true,
                isMultipleTagsSupported = true,
                isSearchWithFiltersSupported = true,
            )

    private val tagsLazy = suspendLazy { fetchTags() }

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = tagsLazy.get(),
        )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url =
            apiUrl("search") {
                addQueryParameter("query", buildQuery(filter))
                addQueryParameter("sort", order.toSortValue())
                addQueryParameter("page", page.toString())
            }
        return webClient
            .httpGet(url, jsonHeaders())
            .parseJson()
            .optJSONArray("result")
            ?.mapJSON { it.toManga() }
            .orEmpty()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = loadGallery(manga.url)
        val pretty = json.optJSONObject("title")?.getStringOrNull("pretty")
        return json.toManga().copy(
            id = manga.id,
            url = manga.url,
            publicUrl = "https://$DOMAIN/g/${manga.url}/",
            chapters =
                listOf(
                    MangaChapter(
                        id = manga.id,
                        title = pretty ?: manga.title,
                        number = 1f,
                        volume = 0,
                        url = manga.url,
                        scanlator = json.getStringOrNull("scanlator"),
                        uploadDate = json.optLong("upload_date", 0L) * 1000L,
                        branch = null,
                        source = source,
                    ),
                ),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val pages = loadGallery(chapter.url).optJSONArray("pages") ?: JSONArray()
        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val path = page.getString("path")
            val preview = page.getStringOrNull("thumbnail")
            MangaPage(
                id = generateUid(path),
                url = "https://$IMAGE_HOST/$path",
                preview = preview?.let { "https://$THUMB_HOST/$it" },
                source = source,
            )
        }
    }

    override suspend fun resolveLink(
        resolver: LinkResolver,
        link: HttpUrl,
    ): Manga? {
        val segments = link.pathSegments
        val index = segments.indexOf("g")
        if (index < 0) {
            return null
        }
        val id = segments.getOrNull(index + 1)?.takeIf { value -> value.all(Char::isDigit) } ?: return null
        val json = loadGallery(id)
        return json.toManga().copy(publicUrl = link.toString())
    }

    private suspend fun loadGallery(id: String): JSONObject {
        val url = "$API_BASE/galleries/$id".toHttpUrl()
        val json = webClient.httpGet(url, jsonHeaders()).parseJson()
        if (json.has("error")) {
            throw NotFoundException(id, url.toString())
        }
        return json
    }

    private fun JSONObject.toManga(): Manga {
        val id = optInt("id", 0).toString()
        val english = getStringOrNull("english_title")
        val japanese = getStringOrNull("japanese_title")
        val titleObject = optJSONObject("title")
        val pretty = titleObject?.getStringOrNull("pretty")
        // the detail endpoint exposes the title only as an object; search results use a flat field
        val englishTitle = english ?: titleObject?.getStringOrNull("english")
        val title = englishTitle ?: pretty ?: japanese ?: id
        val altTitles = LinkedHashSet<String>()
        if (pretty != null && !pretty.equals(title, ignoreCase = true)) {
            altTitles.add(pretty)
        }
        if (japanese != null && !japanese.equals(title, ignoreCase = true)) {
            altTitles.add(japanese)
        }
        titleObject?.getStringOrNull("japanese")?.let { japaneseAlt ->
            if (japaneseAlt != title) {
                altTitles.add(japaneseAlt)
            }
        }
        val coverPath =
            getStringOrNull("thumbnail")
                ?: optJSONObject("thumbnail")?.getStringOrNull("path")
                ?: optJSONObject("cover")?.getStringOrNull("path")
        val tags = optJSONArray("tags") ?: JSONArray()
        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = altTitles,
            url = id,
            publicUrl = "https://$DOMAIN/g/$id/",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = coverPath?.let { "https://$THUMB_HOST/$it" },
            largeCoverUrl = coverPath?.let { "https://$THUMB_HOST/$it" },
            description = buildDescription(tags),
            tags =
                tags.mapJSONNotNullToSet { tag ->
                    val name = tag.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
                    val key = tag.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
                    MangaTag(name.toTitleCase(Locale.ROOT), key, source)
                },
            state = null,
            authors =
                (0 until tags.length())
                    .map { i -> tags.getJSONObject(i) }
                    .filter { tag -> tag.optString("type") == "artist" }
                    .mapNotNull { tag -> tag.getStringOrNull("name") }
                    .toSet(),
            source = source,
        )
    }

    /**
     * The API exposes no synopsis, so summarize the gallery's own metadata instead:
     * artist, parody/series, category, languages, page count and the tag list.
     */
    private fun JSONObject.buildDescription(tags: JSONArray): String? {
        val byType = HashMap<String, MutableList<String>>()
        for (i in 0 until tags.length()) {
            val tag = tags.getJSONObject(i)
            val type = tag.optString("type").nullIfEmpty() ?: continue
            val name = tag.getStringOrNull("name") ?: continue
            byType.getOrPut(type) { ArrayList() }.add(name)
        }
        fun named(type: String, label: String): String? = byType[type]
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$label: $it" }

        val lines = ArrayList<String>(6)
        named("artist", "Artist")?.let(lines::add)
        named("group", "Circle")?.let(lines::add)
        named("parody", "Parody")?.let(lines::add)
        named("character", "Characters")?.let(lines::add)
        named("category", "Category")?.let(lines::add)
        named("language", "Language")?.let(lines::add)
        val pages = optInt("num_pages", 0)
        if (pages > 0) {
            lines.add("Pages: $pages")
        }
        byType["tag"]
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.let { lines.add("Tags: ${it.joinToString(", ")}") }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * [org.json.JSONObject.optString] turns a JSON `null` into the literal string "null",
     * which would leak into titles, tags and image urls.
     */
    private fun JSONObject.getStringOrNull(key: String): String? {
        if (isNull(key)) {
            return null
        }
        return optString(key, "").nullIfEmpty()
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val result = LinkedHashSet<MangaTag>()
        for (page in 1..TAGS_MAX_PAGES) {
            val url =
                apiUrl("tags/tag") {
                    addQueryParameter("sort", "popular")
                    addQueryParameter("page", page.toString())
                    addQueryParameter("per_page", TAGS_PER_PAGE.toString())
                }
            val json = webClient.httpGet(url, jsonHeaders()).parseJson()
            val items = json.optJSONArray("result") ?: break
            if (items.length() == 0) {
                break
            }
            items
                .mapJSONNotNullToSet { tag ->
                    val name = tag.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
                    val key = tag.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
                    MangaTag(name.toTitleCase(Locale.ROOT), key, source)
                }.let(result::addAll)
            if (page >= json.optInt("num_pages", page)) {
                break
            }
        }
        return result
    }

    private fun buildQuery(filter: MangaListFilter): String =
        buildString {
            // `pages:>0` hides removed galleries, the same way the website search does
            append("pages:>0")
            filter.query?.trim()?.nullIfEmpty()?.let { query ->
                append(' ')
                append(query)
            }
            filter.tags.forEach { tag ->
                append(" tag:\"")
                append(tag.key)
                append('"')
            }
            filter.locale?.let { locale ->
                append(" language:")
                append(locale.language)
            }
        }

    private fun SortOrder.toSortValue(): String =
        when (this) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.POPULARITY_TODAY -> "popular-today"
            SortOrder.POPULARITY_WEEK -> "popular-week"
            SortOrder.POPULARITY_MONTH -> "popular-month"
            else -> "date"
        }

    private fun apiUrl(
        path: String,
        block: HttpUrl.Builder.() -> Unit,
    ): HttpUrl = "$API_BASE/$path".toHttpUrl().newBuilder().apply(block).build()

    private fun jsonHeaders(): Headers =
        Headers
            .Builder()
            .add("Accept", "application/json")
            .build()

    private companion object {
        private const val DOMAIN = "nhentai.net"
        private const val API_BASE = "https://nhentai.net/api/v2"
        private const val IMAGE_HOST = "i1.nhentai.net"
        private const val THUMB_HOST = "t1.nhentai.net"
        private const val PAGE_SIZE = 25
        private const val TAGS_PER_PAGE = 100
        private const val TAGS_MAX_PAGES = 4
    }
}
