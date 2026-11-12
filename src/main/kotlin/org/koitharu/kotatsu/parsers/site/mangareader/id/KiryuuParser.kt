package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class KiryuuParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.KIRYUU, pageSize = 24) {
    override val configKeyDomain = ConfigKey.Domain("v7.kiryuu.to")

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL,
            SortOrder.ALPHABETICAL_DESC,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() =
            MangaListFilterCapabilities(
                isMultipleTagsSupported = true,
                isSearchSupported = true,
                isSearchWithFiltersSupported = true,
                isAuthorSearchSupported = true,
            )

    private val genresLazy = suspendLazy { fetchTerms("genre") }
    private val statusesLazy = suspendLazy { fetchStatusIds() }
    private val typesLazy = suspendLazy { fetchTypeIds() }
    private val authorIds = HashMap<String, String>()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresLazy.get()
        val statuses = statusesLazy.get()
        return MangaListFilterOptions(
            availableTags = genres.values.mapToSet { MangaTag(it.name, it.id, source) },
            availableStates = statuses.keys,
            availableContentTypes =
                EnumSet.of(
                    ContentType.MANGA,
                    ContentType.MANHWA,
                    ContentType.MANHUA,
                    ContentType.COMICS,
                    ContentType.NOVEL,
                ),
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val genres = genresLazy.get()
        val statuses = statusesLazy.get()
        val types = typesLazy.get()
        val builder =
            urlBuilder()
                .addPathSegments("wp-json/wp/v2/manga")
                .addQueryParameter("per_page", pageSize.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("_fields", "id,slug,title,metadata,class_list,genre")
        val (orderBy, direction) = order.toQueryParams()
        builder.addQueryParameter("orderby", orderBy)
        builder.addQueryParameter("order", direction)
        filter.query
            ?.trim()
            ?.nullIfEmpty()
            ?.let { builder.addQueryParameter("search", it) }
        filter.author?.trim()?.nullIfEmpty()?.let { author ->
            resolveAuthorId(author)?.let { builder.addQueryParameter("series-author", it) }
        }
        filter.tags.forEach { builder.addQueryParameter("genre[]", it.key) }
        filter.states.forEach { state ->
            statuses[state]?.let { builder.addQueryParameter("manga-status[]", it) }
        }
        filter.types.forEach { type ->
            types[type]?.let { builder.addQueryParameter("manga-type[]", it) }
        }
        val url = builder.build()
        val json =
            runCatching { webClient.httpGet(url).parseJsonArray() }.getOrElse {
                if (page > 1) return emptyList()
                throw it
            }
        return json.mapJSON { it.toManga(genres) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val genres = genresLazy.get()
        val url =
            urlBuilder()
                .addPathSegments("wp-json/wp/v2/manga")
                .addQueryParameter("slug", manga.url)
                .addQueryParameter("_embed", "1")
                .build()
        val json =
            webClient.httpGet(url).parseJsonArray().optJSONObject(0)
                ?: throw NotFoundException(manga.url, url.toString())
        return json.toManga(genres).copy(
            id = manga.id,
            url = manga.url,
            publicUrl = "https://$domain/manga/${manga.url}/",
            chapters = loadChapters(manga.url),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url =
            urlBuilder()
                .addPathSegments("wp-json/kiru/v2/chapter")
                .addQueryParameter("id", chapter.url)
                .build()
        val json = webClient.httpGet(url).parseJson()
        return Jsoup
            .parseBodyFragment(json.optString("content"))
            .select("img[src]")
            .map { img ->
                val imageUrl = img.absUrl("src").nullIfEmpty() ?: img.attr("src")
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            }
    }

    private suspend fun loadChapters(slug: String): List<MangaChapter> {
        val document = webClient.httpGet("/manga/$slug/".toAbsoluteUrl(domain)).parseHtml()
        return document
            .select("#chapter-list div[data-chapter-number]")
            .mapNotNull { element ->
                val link = element.selectFirst("a[href*=/chapter-]") ?: return@mapNotNull null
                val chapterId =
                    CHAPTER_ID.find(link.attr("href"))?.groupValues?.get(1)
                        ?: return@mapNotNull null
                MangaChapter(
                    id = generateUid(chapterId),
                    title = link.selectFirst("span")?.text()?.nullIfEmpty(),
                    number = element.attr("data-chapter-number").toFloatOrNull() ?: 0f,
                    volume = 0,
                    url = chapterId,
                    scanlator = null,
                    uploadDate = parseDate(element.selectFirst("time[datetime]")?.attr("datetime")),
                    branch = null,
                    source = source,
                )
            }.reversed()
    }

    private fun JSONObject.toManga(genreNames: Map<String, Term>): Manga {
        val slug = getString("slug")
        val meta = optJSONObject("metadata")?.optJSONObject("meta")
        val cover = meta?.optString("thumbnail")?.nullIfEmpty()
        val classList = optJSONArray("class_list")?.asTypedList<String>().orEmpty()
        return Manga(
            id = generateUid(slug),
            title = getJSONObject("title").optString("rendered").let { Jsoup.parse(it).text() },
            altTitles = parseAltTitles(meta?.optString("alternative_title")),
            url = slug,
            publicUrl = "https://$domain/manga/$slug/",
            rating = meta?.optString("score")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            largeCoverUrl = cover,
            description =
                optJSONObject("content")
                    ?.optString("rendered")
                    ?.let { Jsoup.parse(it).text() }
                    ?.nullIfEmpty(),
            tags =
                optJSONArray("genre")
                    ?.asTypedList<Int>()
                    ?.mapNotNullToSet { id ->
                        genreNames[id.toString()]?.let { MangaTag(it.name, it.id, source) }
                    }.orEmpty(),
            state = stateFromClasses(classList),
            authors = authorNames(),
            chapters = null,
            source = source,
        )
    }

    private fun JSONObject.authorNames(): Set<String> {
        val groups = optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return emptySet()
        val names = mutableSetOf<String>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONArray(i) ?: continue
            for (j in 0 until group.length()) {
                val term = group.optJSONObject(j) ?: continue
                if (term.optString("taxonomy") !in AUTHOR_TAXONOMIES) continue
                term
                    .optString("name")
                    .trim()
                    .nullIfEmpty()
                    ?.let(names::add)
            }
        }
        return names
    }

    private fun parseAltTitles(value: String?): Set<String> =
        value
            ?.split(',')
            ?.mapNotNullTo(mutableSetOf()) { it.trim().nullIfEmpty() }
            .orEmpty()

    private fun stateFromClasses(classList: List<String>): MangaState? =
        when {
            "status-ongoing" in classList -> MangaState.ONGOING
            "status-completed" in classList -> MangaState.FINISHED
            "status-on-hiatus" in classList -> MangaState.PAUSED
            "status-cancelled" in classList -> MangaState.ABANDONED
            else -> null
        }

    private suspend fun resolveAuthorId(name: String): String? {
        authorIds[name]?.let { return it }
        val url =
            urlBuilder()
                .addPathSegments("wp-json/wp/v2/series-author")
                .addQueryParameter("search", name)
                .addQueryParameter("per_page", "1")
                .addQueryParameter("_fields", "id")
                .build()
        val id =
            runCatching {
                webClient
                    .httpGet(url)
                    .parseJsonArray()
                    .optJSONObject(0)
                    ?.optString("id")
            }.getOrNull()?.nullIfEmpty() ?: return null
        authorIds[name] = id
        return id
    }

    private suspend fun fetchTerms(taxonomy: String): Map<String, Term> {
        val result = LinkedHashMap<String, Term>()
        var page = 1
        while (true) {
            val url =
                urlBuilder()
                    .addPathSegments("wp-json/wp/v2/$taxonomy")
                    .addQueryParameter("per_page", TERMS_PAGE_SIZE.toString())
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("_fields", "id,name,slug")
                    .build()
            val json =
                runCatching {
                    webClient.httpGet(url).parseJsonArray()
                }.getOrElse {
                    if (page > 1) break else throw it
                }
            if (json.length() == 0) break
            json
                .mapJSON {
                    val id = it.optString("id")
                    id to Term(id, it.optString("name"), it.optString("slug"))
                }.forEach { (id, term) -> result[id] = term }
            if (json.length() < TERMS_PAGE_SIZE) break
            page++
            if (page > TERMS_MAX_PAGES) break
        }
        return result
    }

    private suspend fun fetchStatusIds(): Map<MangaState, String> {
        val result = HashMap<MangaState, String>()
        for (term in fetchTerms("manga-status").values) {
            val state =
                when (term.slug) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    "on-hiatus" -> MangaState.PAUSED
                    "cancelled" -> MangaState.ABANDONED
                    else -> null
                } ?: continue
            result[state] = term.id
        }
        return result
    }

    private suspend fun fetchTypeIds(): Map<ContentType, String> {
        val result = HashMap<ContentType, String>()
        for (term in fetchTerms("manga-type").values) {
            val type =
                when (term.slug) {
                    "manga" -> ContentType.MANGA
                    "manhwa" -> ContentType.MANHWA
                    "manhua" -> ContentType.MANHUA
                    "comic" -> ContentType.COMICS
                    "novel" -> ContentType.NOVEL
                    else -> null
                } ?: continue
            result[type] = term.id
        }
        return result
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrEmpty()) return 0L
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return runCatching { format.parse(value)?.time }.getOrNull() ?: 0L
    }

    private fun SortOrder.toQueryParams(): Pair<String, String> =
        when (this) {
            SortOrder.ALPHABETICAL -> "title" to "asc"
            SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
            SortOrder.NEWEST -> "date" to "desc"
            SortOrder.UPDATED -> "modified" to "desc"
            else -> "date" to "desc"
        }

    private data class Term(
        val id: String,
        val name: String,
        val slug: String,
    )

    private companion object {
        private const val TERMS_PAGE_SIZE = 100
        private const val TERMS_MAX_PAGES = 10
        private val CHAPTER_ID = Regex("\\.(\\d+)/?$")
        private val AUTHOR_TAXONOMIES = setOf("series-author", "artist")
    }
}
