package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * CrotPedia (formerly hentaicrot.com) is a WordPress site using a common Indonesian theme.
 *
 * Listings: `/advanced-search/[page/N/]?order=...`. The `order` parameter is mandatory:
 * an unfiltered `/advanced-search/` renders an empty result set.
 * Genres are WordPress taxonomies, hence `/baca/genre/<slug>/`.
 */
@MangaSourceParser("CROTPEDIA", "CrotPedia", "id", ContentType.HENTAI)
internal class CrotPediaParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.CROTPEDIA, PAGE_SIZE),
    MangaParserAuthProvider {
    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers =
        Headers
            .Builder()
            .add("Referer", "https://$DOMAIN/")
            .build()

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.RATING,
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
                isYearSupported = true,
            )

    private val tagsLazy = suspendLazy { fetchTags() }

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = tagsLazy.get(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes =
                EnumSet.of(
                    ContentType.MANGA,
                    ContentType.MANHWA,
                    ContentType.MANHUA,
                    ContentType.DOUJINSHI,
                ),
        )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url =
            urlBuilder()
                .apply {
                    addPathSegment("advanced-search")
                    if (page > 1) {
                        addPathSegment("page")
                        addPathSegment(page.toString())
                    }
                    addPathSegment("")
                    addQueryParameter("order", order.toQueryValue())
                    filter.query
                        ?.trim()
                        ?.nullIfEmpty()
                        ?.let { addQueryParameter("title", it) }
                    filter.year.takeIf { it != 0 }?.let { addQueryParameter("yearx", it.toString()) }
                    filter.types.firstOrNull()?.let { addQueryParameter("type", it.toQueryValue()) }
                    filter.states.firstOrNull()?.let { addQueryParameter("status", it.toQueryValue()) }
                    filter.tags.forEach { addQueryParameter("genre[]", it.key) }
                }.build()
        return webClient.httpGet(url).parseHtml().select("div.flexbox2-item").mapNotNull { div ->
            val link = div.selectFirst("a[href*=/baca/series/]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            val title =
                div.selectFirst("div.flexbox2-title span:not(.studio)")?.textOrNull()
                    ?: link.attr("title").nullIfEmpty()
                    ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(DOMAIN),
                rating =
                    div
                        .selectFirst("div.info div.score")
                        ?.ownText()
                        ?.trim()
                        ?.toFloatOrNull()
                        ?.div(10f)
                        ?.takeIf { it > 0f }
                        ?: RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = div.selectFirst("div.flexbox2-thumb img")?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(DOMAIN)).parseHtml()
        val infoItems = doc.select("ul.series-infolist li")

        fun info(label: String): String? =
            infoItems
                .firstOrNull { it.selectFirst("b")?.text()?.startsWith(label, ignoreCase = true) == true }
                ?.selectFirst("span")
                ?.textOrNull()

        val status = doc.selectFirst("div.series-infoz span.status")?.text()
        val title = doc.selectFirst("div.series-title h2")?.textOrNull() ?: manga.title
        val altTitle = info("Alternative")
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
        return manga.copy(
            title = title,
            altTitles = setOfNotNull(altTitle?.takeIf { !it.equals(title, ignoreCase = true) }),
            description = doc.selectFirst("div.series-synops")?.html()?.nullIfEmpty(),
            authors = setOfNotNull(info("Author")?.nullIfEmpty()?.takeIf { it != "-" }),
            tags =
                doc.select("div.series-genres a").mapToSet { a ->
                    MangaTag(
                        key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
                        title = a.text().trim(),
                        source = source,
                    )
                },
            state =
                when {
                    status == null -> null
                    status.contains("On", ignoreCase = true) -> MangaState.ONGOING
                    status.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
                    else -> null
                },
            chapters =
                doc.select("ul.series-chapterlist li").mapChapters(reversed = true) { _, li ->
                    val link = li.selectFirst("a[href*=/baca/]") ?: return@mapChapters null
                    val href = link.attrAsRelativeUrl("href")
                    MangaChapter(
                        id = generateUid(href),
                        title = link.selectFirst("span")?.textOrNull(),
                        number = parseChapterNumber(href, link.text()),
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = parseChapterDate(dateFormat, link.selectFirst("span.date")?.textOrNull()),
                        branch = null,
                        source = source,
                    )
                },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(DOMAIN)).parseHtml()
        val container = doc.selectFirst("div.reader-area") ?: return emptyList()
        return container.select("img[src]").mapNotNull { img ->
            val url = img.attr("src").nullIfEmpty() ?: return@mapNotNull null
            // the reader inserts a donation banner between the pages
            if (url.contains("donasi") || url.contains("cover.eromanga.cfd")) {
                return@mapNotNull null
            }
            MangaPage(
                id = generateUid(url),
                url = url.toRelativeUrl(DOMAIN),
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(
        resolver: LinkResolver,
        link: HttpUrl,
    ): Manga? {
        val segments = link.pathSegments
        val seriesIndex = segments.indexOf("series")
        val detailUrl =
            when {
                seriesIndex >= 0 && segments.size > seriesIndex + 1 -> {
                    "/baca/series/${segments[seriesIndex + 1]}/"
                }

                // reader links look like /baca/<series-slug>-chapter-<n>-bahasa-indonesia/
                segments.firstOrNull() == "baca" && segments.size > 1 -> {
                    val slug = segments[1].substringBeforeLast("-chapter-").nullIfEmpty() ?: return null
                    "/baca/series/$slug/"
                }

                else -> {
                    return null
                }
            }
        return getDetails(placeholder(detailUrl))
    }

    private fun placeholder(url: String): Manga =
        Manga(
            id = generateUid(url),
            title = url,
            altTitles = emptySet(),
            url = url,
            publicUrl = url.toAbsoluteUrl(DOMAIN),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )

    override val authUrl: String
        get() = "https://$DOMAIN/login/"

    override suspend fun isAuthorized(): Boolean =
        context.cookieJar
            .getCookies(DOMAIN)
            .any { it.name.contains("wordpress_logged_in") }

    override suspend fun getUsername(): String {
        val body = webClient.httpGet("https://$DOMAIN/").parseHtml().body()
        return body.selectFirst("#wp-admin-bar-my-account a span")?.textOrNull()
            ?: body.selectFirst("a[href*='/profile/'], a[href*='/account/']")?.textOrNull()?.nullIfEmpty()
            ?: throw AuthRequiredException(source)
    }

    private suspend fun fetchTags(): Set<MangaTag> =
        webClient
            .httpGet("/advanced-search/".toAbsoluteUrl(DOMAIN))
            .parseHtml()
            .select("tr.gnrx div.custom-control")
            .mapNotNullToSet { control ->
                val key =
                    control.selectFirst("input[name=genre[]]")?.attr("value")?.nullIfEmpty()
                        ?: return@mapNotNullToSet null
                val name = control.selectFirst("label")?.textOrNull() ?: return@mapNotNullToSet null
                MangaTag(key = key, title = name.toTitleCase(), source = source)
            }

    private fun parseChapterNumber(
        url: String,
        title: String?,
    ): Float {
        CHAPTER_NUMBER
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { return it }
        return title
            ?.let { CHAPTER_NUMBER.find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull() ?: 0f
    }

    private fun parseChapterDate(
        format: SimpleDateFormat,
        value: String?,
    ): Long {
        val text = value?.trim()?.nullIfEmpty() ?: return 0L
        return format.parseSafe(text)
    }

    private fun SortOrder.toQueryValue(): String =
        when (this) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.RATING -> "rating"
            SortOrder.NEWEST -> "latest"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.ALPHABETICAL_DESC -> "titlereverse"
            else -> "update"
        }

    private fun ContentType.toQueryValue(): String =
        when (this) {
            ContentType.MANGA -> "Manga"
            ContentType.MANHWA -> "Manhwa"
            ContentType.MANHUA -> "Manhua"
            ContentType.DOUJINSHI -> "Doujinshi"
            else -> ""
        }

    private fun MangaState.toQueryValue(): String =
        when (this) {
            MangaState.ONGOING -> "ongoing"
            MangaState.FINISHED -> "completed"
            else -> ""
        }

    private companion object {
        private const val DOMAIN = "crotpedia.net"
        private const val PAGE_SIZE = 16
        private val CHAPTER_NUMBER = Regex("chapter-(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    }
}
