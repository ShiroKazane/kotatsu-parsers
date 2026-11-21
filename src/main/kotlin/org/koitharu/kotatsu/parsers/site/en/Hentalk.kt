package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArraySet
import androidx.collection.MutableIntList
import androidx.collection.MutableIntObjectMap
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.*

private const val SERVER_DATA_SAVER = "?type="
private const val SERVER_DATA = ""

@MangaSourceParser("HENTALK", "Hentalk", "en", type = ContentType.HENTAI)
internal class Hentalk(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.HENTALK, 24) {
    override val configKeyDomain = ConfigKey.Domain("hentalk.pw", "fakku.cc")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    private val preferredServerKey =
        ConfigKey.PreferredImageServer(
            presetValues =
                mapOf(
                    SERVER_DATA to "Original quality",
                    SERVER_DATA_SAVER to "Compressed quality",
                ),
            defaultValue = SERVER_DATA,
        )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredServerKey)
    }

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.NEWEST_ASC,
            SortOrder.ALPHABETICAL,
            SortOrder.ALPHABETICAL_DESC,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() =
            MangaListFilterCapabilities(
                isSearchSupported = true,
                isMultipleTagsSupported = true,
                isSearchWithFiltersSupported = true,
                isAuthorSearchSupported = true,
            )

    private val tagsLazy = suspendLazy { fetchTags() }

    override suspend fun getFilterOptions() =
        MangaListFilterOptions(
            availableTags = tagsLazy.get(),
        )

    /**
     * The site has no tag index route, so collect the vocabulary from the first browse page,
     * which embeds every gallery's tags (`namespace == "tag"`). Additional pages only add
     * latency to opening the source, so a single request is used.
     */
    private suspend fun fetchTags(): Set<MangaTag> {
        val result = LinkedHashSet<MangaTag>()
        val url = "https://$domain/__data.json?x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=001"
        val data =
            runCatchingCancellable { webClient.httpGet(url).parseJson().dataArray() }
                .getOrNull() ?: return result
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (!item.has("namespace")) {
                continue
            }
            val namespace = data.resolve(item.optInt("namespace"))?.toString()
            val name = data.resolve(item.optInt("name"))?.toString()?.nullIfEmpty() ?: continue
            if (namespace == "tag") {
                result.add(MangaTag(key = name, title = name.toTitleCase(), source = source))
            }
        }
        return result
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url =
            buildString {
                append("https://")
                append(domain)
                append("/__data.json?x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=001")

                when {
                    !filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() || !filter.author.isNullOrEmpty() -> {
                        append("&q=")

                        if (!filter.author.isNullOrEmpty()) {
                            append("artist:\"${space2plus(filter.author)}\"")
                            append('+')
                        }

                        if (filter.tags.isNotEmpty()) {
                            filter.tags.forEach { tag ->
                                append("tag:\"${space2plus(tag.key)}\"")
                                append('+')
                            }
                        }

                        if (!filter.query.isNullOrEmpty()) {
                            append(space2plus(filter.query))
                        } else {
                            append('+')
                        }
                    }
                }

                when (order) {
                    SortOrder.UPDATED -> {
                        append("&sort=released_at")
                    }

                    SortOrder.NEWEST_ASC -> {
                        append("&sort=created_at&order=asc")
                    }

                    SortOrder.NEWEST -> {
                        append("&sort=created_at&order=desc")
                    }

                    SortOrder.ALPHABETICAL -> {
                        append("&sort=title&order=asc")
                    }

                    SortOrder.ALPHABETICAL_DESC -> {
                        append("&sort=title&order=desc")
                    }

                    else -> {}
                }

                if (page > 1) {
                    append("&page=")
                    append(page)
                }
            }

        val json =
            try {
                webClient.httpGet(url).parseJson()
            } catch (e: HttpStatusException) {
                if (e.statusCode == HttpURLConnection.HTTP_INTERNAL_ERROR) {
                    return emptyList()
                } else {
                    throw ParseException("Can't get data from source!", url)
                }
            }

        val dataArray =
            json
                .getJSONArray("nodes")
                .optJSONObject(2)
                ?.optJSONArray("data")
                ?: return emptyList()

        val dataValues = MutableIntObjectMap<Any>(dataArray.length())
        for (i in 0 until dataArray.length()) {
            dataValues[i] = dataArray.get(i)
        }

        val archiveH = MutableIntList(dataArray.length())
        for (i in 0 until dataArray.length()) {
            val item = dataArray.opt(i)
            if (item is JSONObject && item.has("id") && item.has("hash") &&
                item.has("title") && item.has("thumbnail") && item.has("tags")
            ) {
                archiveH.add(i)
            }
        }

        val mangaList = ArrayList<Manga>()
        archiveH.forEach { tempIndex ->
            val temp = dataArray.getJSONObject(tempIndex)
            val idRef = temp.getInt("id")
            val hashRef = temp.getInt("hash")
            val titleRef = temp.getInt("title")
            val thumbnailRef = temp.getInt("thumbnail")
            val tagsRef = temp.getInt("tags")

            val mangaId = dataArray.getLong(idRef)

            val key = dataArray.getString(hashRef)
            val title = dataArray.getString(titleRef)
            val idThumbnail = dataArray.getInt(thumbnailRef)

            val tagsList = dataArray.optJSONArray(tagsRef)
            val tags = ArraySet<MangaTag>()
            var author: String? = null

            if (tagsList != null) {
                var i = 0
                while (i < tagsList.length()) {
                    val tagRefIndex = tagsList.getInt(i)

                    if (dataValues.containsKey(tagRefIndex) &&
                        dataValues[tagRefIndex] is JSONObject &&
                        (dataValues[tagRefIndex] as JSONObject).has("namespace")
                    ) {
                        val nsObj = dataValues[tagRefIndex] as JSONObject
                        val nsIndex = nsObj.getInt("namespace")
                        val nameIndex = nsObj.getInt("name")

                        val nsValue = if (dataValues.containsKey(nsIndex)) dataValues[nsIndex].toString() else null
                        val nameValue =
                            if (dataValues.containsKey(nameIndex)) dataValues[nameIndex].toString() else null

                        if (nsValue == "artist") {
                            author = nameValue?.nullIfEmpty()
                        } else if (nsValue == "tag" && nameValue != null) {
                            tags.add(
                                MangaTag(
                                    key = nameValue,
                                    title = nameValue,
                                    source = source,
                                ),
                            )
                        }
                    }
                    i++
                }
            }

            mangaList.add(
                Manga(
                    id = generateUid(mangaId),
                    url = "/g/$mangaId/__data.json?x-sveltekit-invalidated=001",
                    publicUrl = "https://$domain/g/$mangaId",
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = "https://$domain/image/$key/$idThumbnail?type=cover",
                    largeCoverUrl = null,
                    authors = setOfNotNull(author),
                    tags = tags,
                    state = null,
                    description = null,
                    contentRating = ContentRating.ADULT,
                    source = source,
                    rating = RATING_UNKNOWN,
                ),
            )
        }

        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId =
            manga.url
                .substringAfter("/g/")
                .substringBefore('/')
                .substringBefore('?')
        val json =
            webClient
                .httpGet(
                    "/g/$mangaId/__data.json?x-sveltekit-invalidated=001".toAbsoluteUrl(domain),
                ).parseJson()
        val dataArray = json.dataArray() ?: return manga
        val gallery = dataArray.optJSONObject(1) ?: return manga

        val title = dataArray.resolve(gallery.optInt("title"))?.toString()?.nullIfEmpty() ?: manga.title
        val description = dataArray.resolve(gallery.optInt("description"))?.toString()?.nullIfEmpty()
        val createdAt = dataArray.resolve(gallery.optInt("createdAt"))?.toString().orEmpty()
        val tags = LinkedHashSet<MangaTag>()
        val authors = LinkedHashSet<String>()
        val tagsArray = dataArray.optJSONArray(gallery.optInt("tags"))
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tag = dataArray.optJSONObject(tagsArray.optInt(i)) ?: continue
                val namespace = dataArray.resolve(tag.optInt("namespace"))?.toString()
                val name = dataArray.resolve(tag.optInt("name"))?.toString()?.nullIfEmpty() ?: continue
                when (namespace) {
                    "artist", "circle" -> authors.add(name)
                    "tag" -> tags.add(MangaTag(key = name, title = name.toTitleCase(), source = source))
                }
            }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val chapter =
            MangaChapter(
                id = generateUid("/g/$mangaId/read/1"),
                url = "/g/$mangaId/read/1/__data.json?x-sveltekit-invalidated=011",
                title = "Oneshot", // every gallery is a single chapter
                number = 0f,
                uploadDate = dateFormat.parseSafe(createdAt),
                volume = 0,
                branch = null,
                scanlator = null,
                source = source,
            )

        return manga.copy(
            title = title,
            description = description,
            authors = authors.ifEmpty { manga.authors },
            tags = tags.ifEmpty { manga.tags },
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
        val nodes = json.getJSONArray("nodes")
        // presets (including the compressed one) live in a separate node from the gallery
        val presets = nodes.optJSONObject(1)?.optJSONArray("data")
        val galleryData = nodes.optJSONObject(2)?.optJSONArray("data") ?: return emptyList()
        val gallery = galleryData.optJSONObject(1) ?: return emptyList()

        val galleryHash = galleryData.resolve(gallery.optInt("hash"))?.toString().orEmpty()
        val compressHash =
            presets?.let { data ->
                val defaults = data.optJSONObject(0) ?: return@let null
                val defaultPreset = data.optJSONObject(defaults.optInt("defaultPreset")) ?: return@let null
                data.resolve(defaultPreset.optInt("hash"))?.toString()?.nullIfEmpty()
            }

        val images = galleryData.optJSONArray(gallery.optInt("images")) ?: return emptyList()
        val server = config[preferredServerKey] ?: SERVER_DATA
        return (0 until images.length()).mapNotNull { i ->
            val image = galleryData.optJSONObject(images.optInt(i)) ?: return@mapNotNull null
            val filename =
                galleryData.resolve(image.optInt("filename"))?.toString()?.nullIfEmpty()
                    ?: return@mapNotNull null
            val baseUrl = "https://$domain/image/$galleryHash/$filename"
            val imageUrl =
                if (server == SERVER_DATA_SAVER && compressHash != null) {
                    baseUrl + SERVER_DATA_SAVER + compressHash
                } else {
                    baseUrl
                }

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private companion object {
        private const val TAGS_MAX_PAGES = 4
    }

    private fun space2plus(input: String): String = input.replace(' ', '+')

    private fun JSONObject.dataArray(): JSONArray? = getJSONArray("nodes").optJSONObject(2)?.optJSONArray("data")

    private fun JSONArray.resolve(index: Int): Any? = if (index in 0 until length()) opt(index) else null
}
