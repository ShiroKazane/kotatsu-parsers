package org.koitharu.kotatsu.parsers.site.all

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
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Yurivan is a Next.js/Vite-like reader backed by Supabase (PostgREST, schema `yurivan`).
 *
 * Only `gallery` stories are exposed: they are the only ones with page images
 * (`story_pages.object_key` served from `img.yurivan.com`). `text` and `video` stories
 * cannot be represented with the image-based [org.koitharu.kotatsu.parsers.model.MangaPage] model.
 */
@MangaSourceParser("YURIVAN", "Yurivan", "en")
internal class YurivanParser(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.YURIVAN, pageSize = PAGE_SIZE) {
    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.RATING,
            SortOrder.ALPHABETICAL,
            SortOrder.ALPHABETICAL_DESC,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() =
            MangaListFilterCapabilities(
                isMultipleTagsSupported = true,
                isSearchSupported = true,
                isSearchWithFiltersSupported = true,
            )

    private val tagsLazy = suspendLazy { fetchTags() }

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = tagsLazy.get(),
            availableLocales = LANGUAGE_TAGS.keys,
        )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val offset = (page - 1) * pageSize
        if (offset < 0) {
            return emptyList()
        }
        val url =
            apiUrl("stories") {
                addQueryParameter("select", LIST_FIELDS)
                addQueryParameter("status", "eq.ACTIVE")
                addQueryParameter("type", "eq.gallery")
                addQueryParameter("chapter_count", "gt.0")
                addQueryParameter("limit", pageSize.toString())
                addQueryParameter("offset", offset.toString())
                addQueryParameter("order", order.toOrderValue())
                filter.query?.trim()?.nullIfEmpty()?.let {
                    addQueryParameter("search_text", "plfts.$it")
                }
                if (filter.tags.isNotEmpty()) {
                    // any-of: language and format tags are mutually exclusive, so `cs` (all-of)
                    // would return an empty list for perfectly valid filter combinations
                    addQueryParameter("tags", "ov.{${filter.tags.joinToString(",") { tag -> tag.key }}}")
                }
                filter.locale?.let { locale ->
                    LANGUAGE_TAGS[locale]?.let { addQueryParameter("tags", "cs.{$it}") }
                }
            }
        return webClient.httpGet(url, headers()).parseJsonArray().mapJSON { it.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga =
        loadDetails(manga.url).copy(
            id = manga.id,
            url = manga.url,
            publicUrl = manga.publicUrl,
        )

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (storyId, chapterIndex) = chapter.url.split('/')
        val url =
            apiUrl("story_pages") {
                addQueryParameter("select", "object_key")
                addQueryParameter("story_id", "eq.$storyId")
                addQueryParameter("chapter_index", "eq.$chapterIndex")
                addQueryParameter("order", "local_page_index.asc")
            }
        return webClient.httpGet(url, headers()).parseJsonArray().mapJSON { page ->
            val key = page.getString("object_key")
            MangaPage(
                id = generateUid(key),
                url = "$IMAGE_BASE/$key",
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
        val index = segments.indexOf("story")
        if (index < 0) {
            return null
        }
        val storyId = segments.getOrNull(index + 1)?.takeIf { UUID_PATTERN.matches(it) } ?: return null
        return loadDetails(storyId)
    }

    private suspend fun loadDetails(storyId: String): Manga {
        val storyUrl =
            apiUrl("stories") {
                addQueryParameter("select", DETAILS_FIELDS)
                addQueryParameter("id", "eq.$storyId")
                addQueryParameter("limit", "1")
            }
        val story =
            webClient.httpGet(storyUrl, headers()).parseJsonArray().optJSONObject(0)
                ?: throw NotFoundException(storyId, storyUrl.toString())
        val chaptersUrl =
            apiUrl("story_chapters") {
                addQueryParameter("select", "id,chapter_index,title,created_at,has_explicit_scene")
                addQueryParameter("story_id", "eq.$storyId")
                addQueryParameter("order", "chapter_index.asc")
            }
        val chaptersJson = webClient.httpGet(chaptersUrl, headers()).parseJsonArray()
        val chapters = chaptersJson.mapJSON { it.toChapter(storyId) }
        // keep every chapter in the default branch, and additionally expose the explicit
        // ones as their own branch so the details screen can filter like the website does
        val explicitChapters = ArrayList<MangaChapter>()
        for (i in 0 until chaptersJson.length()) {
            val row = chaptersJson.getJSONObject(i)
            if (row.optBoolean("has_explicit_scene", false)) {
                explicitChapters +=
                    row.toChapter(storyId).copy(
                        id = generateUid("${row.getString("id")}#explicit"),
                        branch = BRANCH_EXPLICIT,
                    )
            }
        }
        return story.toManga().copy(chapters = chapters + explicitChapters)
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val counts = HashMap<String, Int>()
        var offset = 0
        while (true) {
            val url =
                apiUrl("stories") {
                    addQueryParameter("select", "tags")
                    addQueryParameter("status", "eq.ACTIVE")
                    addQueryParameter("type", "eq.gallery")
                    addQueryParameter("limit", TAGS_PAGE_SIZE.toString())
                    addQueryParameter("offset", offset.toString())
                }
            val page = webClient.httpGet(url, headers()).parseJsonArray()
            if (page.length() == 0) {
                break
            }
            page.mapJSON { it.optJSONArray("tags") ?: JSONArray() }.forEach { tags ->
                for (i in 0 until tags.length()) {
                    val tag = tags.optString(i).nullIfEmpty() ?: continue
                    counts[tag] = (counts[tag] ?: 0) + 1
                }
            }
            if (page.length() < TAGS_PAGE_SIZE) {
                break
            }
            offset += TAGS_PAGE_SIZE
        }
        return counts.entries
            .filterNot { it.key in LANGUAGE_TAGS.values }
            .sortedByDescending { it.value }
            .take(TAGS_LIMIT)
            .map { MangaTag(formatTag(it.key), it.key, source) }
            .distinctBy { it.title }
            .toSet()
    }

    private fun JSONObject.toManga(): Manga {
        val storyId = getString("id")
        val title = getString("title")
        val cover = getStringOrNull("cover_url")
        return Manga(
            id = generateUid(storyId),
            title = title,
            altTitles = parseAltTitles(title),
            url = storyId,
            publicUrl = "https://$DOMAIN/en/story/$storyId",
            rating = optDouble("rating_avg", 0.0).toFloat().div(5f).takeIf { it > 0f } ?: RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description(),
            tags =
                optJSONArray("tags")
                    ?.let { array ->
                        (0 until array.length()).mapNotNull { i ->
                            val key = array.optString(i).nullIfEmpty() ?: return@mapNotNull null
                            // chinese/japanese/korean/english describe the language, not a genre
                            if (key in LANGUAGE_TAGS.values) {
                                return@mapNotNull null
                            }
                            MangaTag(formatTag(key), key, source)
                        }
                    }.orEmpty()
                    .toSet(),
            state = optString("publication_status").toState(),
            authors = parseAuthors(),
            source = source,
        )
    }

    private fun JSONObject.description(): String? {
        val descriptions = optJSONObject("descriptions")
        descriptions?.getDescription("en")?.let { return it }
        // the site keeps the original English text in `caption` even when only
        // translations exist in `descriptions`
        getStringOrNull("caption")?.let { return it }
        descriptions ?: return null
        descriptions.keys().asSequence().forEach { key ->
            descriptions.getDescription(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.getDescription(locale: String): String? =
        optJSONObject(locale)
            ?.getStringOrNull("text")

    private fun JSONObject.parseAltTitles(title: String): Set<String> {
        val alt = optJSONObject("alt_titles") ?: return emptySet()
        val result = LinkedHashSet<String>()
        alt.keys().asSequence().forEach { key ->
            if (key.startsWith("search-quality")) {
                return@forEach
            }
            alt.getStringOrNull(key)?.let {
                if (!it.equals(title, ignoreCase = true)) {
                    result.add(it)
                }
            }
        }
        return result
    }

    private fun JSONObject.parseAuthors(): Set<String> {
        val creator = getStringOrNull("original_creator") ?: return emptySet()
        return creator
            .split('·')
            .mapNotNullTo(LinkedHashSet()) { it.trim().nullIfEmpty() }
    }

    private fun JSONObject.toChapter(storyId: String): MangaChapter {
        val index = getInt("chapter_index")
        return MangaChapter(
            id = generateUid(getString("id")),
            title = getStringOrNull("title"),
            number = index + 1f,
            volume = 0,
            url = "$storyId/$index",
            scanlator = null,
            uploadDate = parseDate(getStringOrNull("created_at")),
            // explicit chapters are additionally exposed as their own branch (see loadDetails)
            branch = null,
            source = source,
        )
    }

    private fun JSONObject.getStringOrNull(key: String): String? {
        if (isNull(key)) {
            return null
        }
        val value = optString(key).trim()
        return value.nullIfEmpty()
    }

    private fun String.toState(): MangaState? =
        when (this) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

    private fun SortOrder.toOrderValue(): String =
        when (this) {
            SortOrder.UPDATED -> "updated_at.desc"
            SortOrder.NEWEST -> "published_at.desc.nullslast"
            SortOrder.POPULARITY -> "view_count.desc"
            SortOrder.RATING -> "rating_avg.desc.nullslast"
            SortOrder.ALPHABETICAL -> "title.asc"
            SortOrder.ALPHABETICAL_DESC -> "title.desc"
            else -> "updated_at.desc"
        }

    private fun headers(): Headers =
        Headers
            .Builder()
            .add("apikey", API_KEY)
            .add("Accept", "application/json")
            .add("Accept-Profile", SCHEMA)
            .build()

    private fun apiUrl(
        path: String,
        block: HttpUrl.Builder.() -> Unit,
    ): HttpUrl = "$API_BASE/$path".toHttpUrl().newBuilder().apply(block).build()

    private fun parseDate(value: String?): Long {
        if (value.isNullOrEmpty()) {
            return 0L
        }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return runCatching { format.parse(value.take(19))?.time }.getOrNull() ?: 0L
    }

    private fun formatTag(tag: String): String =
        tag
            .split('-', '_')
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) } }

    private companion object {
        private const val DOMAIN = "www.yurivan.com"
        private const val API_BASE = "https://wmnzjmrysnzjthldgffh.supabase.co/rest/v1"
        private const val IMAGE_BASE = "https://img.yurivan.com"
        private const val API_KEY = "sb_publishable_4yI6VdjidXNGtE29hI5k3A_w4TxKHix"
        private const val SCHEMA = "yurivan"
        private const val PAGE_SIZE = 24
        private const val BRANCH_EXPLICIT = "Explicit"
        private const val TAGS_LIMIT = 120
        private const val TAGS_PAGE_SIZE = 1000
        private const val LIST_FIELDS =
            "id,title,cover_url,tags,rating_avg,chapter_count,publication_status,original_creator"
        private const val DETAILS_FIELDS =
            "$LIST_FIELDS,alt_titles,descriptions,caption,updated_at,published_at,view_count"

        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** The site keeps these in the `tags` array, but its own UI exposes them as the language facet. */
        private val LANGUAGE_TAGS = mapOf(
            Locale.ENGLISH to "english",
            Locale.JAPANESE to "japanese",
            Locale.CHINESE to "chinese",
            Locale.KOREAN to "korean",
        )
    }
}
