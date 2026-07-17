package org.koitharu.kotatsu.parsers.site.madtheme.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madtheme.MadthemeParser
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import java.util.EnumSet
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MANGABUDDY", "Mangabuddy_c", "en")
internal class MangaBuddy(context: MangaLoaderContext) : 
    MadthemeParser(context, MangaParserSource.MANGABUDDY, "mangak.io") {

    private val apiUrl = "https://api.mangak.io"

    override val selectDesc = ".summary .content, .summary .content ~ p"
    override val selectState = ".detail .meta > p > strong:contains(Status) ~ a"
    override val selectAlt = ".detail h2"
    override val selectTag = ".detail .meta > p > strong:contains(Genres) ~ a"
    override val selectDate = ".chapter-update"
    override val selectChapter = "#chapter-list > li, #chapter-list-inner .chapter-list > li"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isSearchSupported = true,
            isAuthorSearchSupported = false,
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
        )
    }

    private fun parseNextData(html: String): JSONObject {
        val script = html.substringAfter("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
            .substringBefore("</script>")
        return JSONObject(script.trim())
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query ?: ""
        
        val url = buildString {
            append("https://")
            append(domain)
            append("/search?q=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
            if (page > 1) {
                append("&page=")
                append(page.toString())
            }
        }

        val html = webClient.httpGet(url).body!!.string()
        val nextData = parseNextData(html)
        val pageProps = nextData.getJSONObject("props").getJSONObject("pageProps")
        
        if (!pageProps.has("ssrItems")) return emptyList()
        val items = pageProps.getJSONArray("ssrItems")
        
        val mangas = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val mangaSlug = item.getString("slug")
            val relativePath = "/$mangaSlug"
            
            val rawRating = item.optDouble("rating", 0.0).toFloat()
            val normalizedRating = if (rawRating > 0f) rawRating / 5.0f else RATING_UNKNOWN
            
            mangas.add(
                Manga(
                    id = generateUid(relativePath),
                    url = relativePath,
                    publicUrl = relativePath.toAbsoluteUrl(domain),
                    coverUrl = item.getString("cover").toAbsoluteUrl(domain),
                    title = item.getString("name"),
                    altTitles = emptySet(),
                    rating = normalizedRating,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE
                )
            )
        }
        return mangas
    }
    override suspend fun getDetails(manga: Manga): Manga {
        val html = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).body!!.string()
        val nextData = parseNextData(html)
        val pageProps = nextData.getJSONObject("props").getJSONObject("pageProps")
        val initialManga = pageProps.getJSONObject("initialManga")
        val hsid = pageProps.getString("mangaHsid")

        val state = when (initialManga.optString("status").lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed", "finished" -> MangaState.FINISHED
            else -> null
        }

        val tagsSet = mutableSetOf<MangaTag>()
        val genres = initialManga.optJSONArray("genres")
        if (genres != null) {
            for (i in 0 until genres.length()) {
                val g = genres.getJSONObject(i)
                tagsSet.add(
                    MangaTag(
                        key = g.getString("slug"),
                        title = g.getString("name"),
                        source = source
                    )
                )
            }
        }

        val authorSet = mutableSetOf<String>()
        val firstAuthor = initialManga.optJSONArray("authors")?.optJSONObject(0)?.optString("name") ?: ""
        if (firstAuthor.isNotEmpty()) authorSet.add(firstAuthor)

        val cleanSlug = initialManga.getString("slug")
        val rawRating = initialManga.optDouble("rating", 0.0).toFloat()
        val normalizedRating = if (rawRating > 0f) rawRating / 5.0f else RATING_UNKNOWN

        val alternatives = mutableSetOf<String>()
        val altName = initialManga.optString("altName", "")
        if (altName.isNotEmpty()) alternatives.add(altName)

        return manga.copy(
            title = initialManga.getString("name"),
            altTitles = alternatives,
            authors = authorSet,
            tags = tagsSet,
            description = initialManga.optString("summary", ""),
            state = state,
            largeCoverUrl = initialManga.getString("cover").toAbsoluteUrl(domain),
            chapters = getChaptersList(cleanSlug, hsid),
            contentRating = ContentRating.SAFE
        )
    }

    private suspend fun getChaptersList(slug: String, mangaId: String): List<MangaChapter> {
        val currentTimeVector = System.currentTimeMillis().toString()
        val chaptersUrl = "$apiUrl/titles/$mangaId/chapters?cv=$currentTimeVector"
        
        val jsonText = try {
            webClient.httpGet(chaptersUrl).body!!.string()
        } catch (e: Exception) {
            return emptyList()
        }
        
        val dataObj = JSONObject(jsonText).getJSONObject("data")
        val chaptersArray = dataObj.getJSONArray("chapters")

        val dateFormat = SimpleDateFormat(
           "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
           Locale.US
        ).apply {
           timeZone = TimeZone.getTimeZone("UTC")
		}
		
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val chapterSlug = ch.getString("slug")
            
            // FIXED: Using a distinct composite delimiter block to bypass the core Madtheme base class path filter operations
            val computedPath = "/$slug?chapter=$chapterSlug"

			val uploadDate = try {
                 dateFormat.parse(ch.getString("updated_at"))?.time ?: 0L
            }catch (_: Exception) {
              0L
	    	}
            
            chapters.add(
                MangaChapter(
                    id = generateUid(computedPath),
                    url = computedPath,
                    title = ch.getString("name"),
                    uploadDate = uploadDate,
                    source = source,
                    number = ch.optDouble("chapter_number", 0.0).toFloat(),
                    volume = 0,
                    scanlator = null,
                    branch = null
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // FIXED: Deconstructs query string configurations safely into expected NextJS sub-routes
        val cleanUrlPath = chapter.url.removePrefix("/")
        val slug = cleanUrlPath.substringBefore("?chapter=")
        val chapterSlug = cleanUrlPath.substringAfter("?chapter=")
        
        val targetChapterPublicUrl = "https://$domain/$slug/$chapterSlug"
        val html = webClient.httpGet(targetChapterPublicUrl).body!!.string()
        
        val nextData = parseNextData(html)
        val initialChapter = nextData.getJSONObject("props").getJSONObject("pageProps").getJSONObject("initialChapter")
        val images = initialChapter.getJSONArray("images")

        val pages = mutableListOf<MangaPage>()
        for (i in 0 until images.length()) {
            pages.add(
                MangaPage(
                    id = i.toLong(),
                    url = images.getString(i), 
                    preview = null,
                    source = source
                )
            )
        }
        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFavicons(): Favicons = Favicons(emptyList(), null)
	}
