package com.turkylatam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TurkyLatamNovelProvider : MainAPI() {
    override var mainUrl = "https://kaj.seriesturcastv.to"
    override var name = "Turky Latam Novel"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // La sección "series-audio" ya es solo latino; en el resto se filtra por la etiqueta AUDIO.
    override val mainPage = mainPageOf(
        "series-audio/" to "Series en latino",
        "generos/romance/" to "Romance (latino)",
        "generos/drama/" to "Drama (latino)",
        "generos/accion/" to "Acción (latino)",
        "generos/comedia/" to "Comedia (latino)",
        "generos/familia/" to "Familia (latino)",
    )

    private val excludedPaths = listOf(
        "/generos/", "/ano/", "/page/", "/wp-", "/series/", "/series-audio/",
        "/series-subtituladas/", "/capitulos", "/calendario/", "/contacto/",
        "/politicas", "/terminos", "/home/", "/feed", "/tag/", "/author/"
    )

    // ---------- Portada ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val doc = app.get(url).document
        val onlyAudio = !request.data.startsWith("series-audio")
        val items = parseCards(doc, onlyAudio)
        val hasNext = doc.selectFirst("a.next, a[rel=next], .pagination a:contains(»), .nav-links a.next") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext && items.isNotEmpty())
    }

    // ---------- Búsqueda ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.encodeUri()}").document
        return parseCards(doc, onlyAudio = true)
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ---------- Tarjetas ----------

    private fun Element.hasAudioBadge(): Boolean =
        selectFirst("[class*=audio], [class*=Audio], [class*=latino]") != null ||
            select("span, div, b, i, em, small, label, strong").any {
                val t = it.ownText().trim()
                t.equals("AUDIO", true) || t.equals("LATINO", true) || t.equals("Audio Latino", true)
            }

    private fun isContentLink(href: String): Boolean {
        if (!href.startsWith(mainUrl)) return false
        val path = href.removePrefix(mainUrl)
        if (path.isBlank() || path == "/") return false
        return excludedPaths.none { path.startsWith(it) }
    }

    private fun parseCards(doc: Document, onlyAudio: Boolean): List<SearchResponse> {
        val root = doc.selectFirst("#body") ?: doc.body()
        val seen = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]:has(img)")) {
            val href = fixUrl(a.attr("href"))
            if (!isContentLink(href) || seen.containsKey(href)) continue

            // La tarjeta es el contenedor más grande que solo tiene este enlace.
            var card: Element = a
            while (true) {
                val p = card.parent() ?: break
                if (p == root) break
                val links = p.select("a[href]:has(img)").map { it.attr("href") }.distinct()
                if (links.size > 1) break
                card = p
            }
            if (onlyAudio && !card.hasAudioBadge()) continue

            val img = a.selectFirst("img")
            val poster = img?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
            }?.let { fixUrlNull(it) }
            val title = (card.selectFirst("h1, h2, h3, h4, .title, [class*=title], [class*=name]")?.text()
                ?: a.attr("title").ifBlank { null }
                ?: img?.attr("alt")?.ifBlank { null }
                ?: card.text()).trim()
            if (title.isBlank()) continue

            seen[href] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return seen.values.toList()
    }

    // ---------- Ficha de la serie ----------

    private val episodeRegex = Regex("""(?i)cap[ií]tulo[\s\-_]*(\d+)""")
    private val seasonRegex = Regex("""(?i)temporada[\s\-_]*(\d+)""")

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document
        var seriesUrl = url

        // Si se abrió un capítulo, ir a la ficha de la serie (2.º elemento de la miga de pan).
        if (url.contains("-capitulo-")) {
            doc.select("ol.breadcrumb li a[href]").map { fixUrl(it.attr("href")) }
                .firstOrNull { isContentLink(it) && !it.contains("-capitulo-") }
                ?.let { seriesUrl = it; doc = app.get(it).document }
        }

        val title = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: name).substringBefore(" – ").substringBefore(" - Series Turcas").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: Regex("""background-image:url\(([^)]+)\)""").find(doc.html())?.groupValues?.get(1)
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/generos/]").map { it.text() }.filter { it.isNotBlank() }.distinct()
        val year = doc.selectFirst("a[href*=/ano/]")?.text()?.trim()?.toIntOrNull()

        var links = doc.select("#episodes a.episod[href], a.episod[href]")
        if (links.isEmpty()) links = doc.select("#body a[href*=-capitulo-]")
        // La ficha solo muestra los últimos capítulos ("VER MAS CAPITULOS");
        // la página de cualquier capítulo trae la lista completa.
        val epUrl = if (url.contains("-capitulo-")) url else links.firstOrNull()?.attr("href")?.let { fixUrl(it) }
        if (epUrl != null) {
            val more = runCatching { app.get(epUrl).document.select("#episodes a.episod[href]") }.getOrNull()
            if (more != null && more.size > links.size) links = more
        }

        val episodes = LinkedHashMap<String, Episode>()
        for (a in links) {
            val href = fixUrl(a.attr("href"))
            if (episodes.containsKey(href)) continue
            val num = (episodeRegex.find(a.ownText()) ?: episodeRegex.find(href))?.groupValues?.get(1)?.toIntOrNull()
            val season = (seasonRegex.find(a.text()) ?: seasonRegex.find(href))?.groupValues?.get(1)?.toIntOrNull()
            val subtitle = a.selectFirst(".glowi")?.text()?.takeIf { it.isNotBlank() }
            episodes[href] = newEpisode(href) {
                this.name = listOfNotNull(a.ownText().trim().ifBlank { null } ?: num?.let { "Capitulo $it" }, subtitle)
                    .joinToString(" · ")
                this.episode = num
                this.season = season ?: 1
            }
        }
        val list = episodes.values.sortedWith(compareBy({ it.season }, { it.episode }))
            .ifEmpty { listOf(newEpisode(url) { this.name = title; this.episode = 1; this.season = 1 }) }

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, list) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    // ---------- Enlaces de vídeo ----------

    // Cada servidor viene en <span data-sN="trozos|en|base64">: base64 → invertir → restar 5 → XOR con la clave.
    private val serverKey = intArrayOf(86, 110, 51, 72, 106, 87, 56, 102)
    private val serverRegex = Regex("""data-s(\d+)="([A-Za-z0-9+/=|]+)"""")
    private val iframeSrc = Regex("""src="([^"]+)"""")

    private fun decodeServer(data: String): String? = runCatching {
        val bytes = android.util.Base64.decode(data.replace("|", ""), android.util.Base64.DEFAULT).reversedArray()
        val sb = StringBuilder()
        bytes.forEachIndexed { i, b -> sb.append((((b.toInt() and 0xFF) - 5 + 256) % 256 xor serverKey[i % serverKey.size]).toChar()) }
        val html = sb.toString()
        iframeSrc.find(html)?.groupValues?.get(1) ?: html.takeIf { it.startsWith("http") }
    }.getOrNull()

    private val mediaRegex = Regex("""(https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")

    // Servidores que Cloudstream no conoce: buscar el .m3u8/.mp4 dentro de la página del reproductor.
    private suspend fun genericExtract(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = runCatching { app.get(embed, referer = "$mainUrl/") }.getOrNull() ?: return false
        var text = res.text
        if (text.contains("eval(function(p,a,c,k,e,d)")) {
            text += "\n" + (runCatching { getAndUnpack(text) }.getOrNull() ?: "")
        }
        val host = runCatching { java.net.URI(embed).host.removePrefix("www.") }.getOrDefault(name)
        var ok = false
        mediaRegex.findAll(text.replace("\\/", "/")).map { it.value }.distinct().forEach { raw ->
            val link = if (raw.startsWith("//")) "https:$raw" else raw
            val isHls = link.contains(".m3u8")
            callback(
                newExtractorLink(host, host, link, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
            ok = true
        }
        return ok
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val embeds = serverRegex.findAll(html)
            .sortedBy { it.groupValues[1].toIntOrNull() ?: 99 }
            .mapNotNull { decodeServer(it.groupValues[2]) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct().toList()

        var found = false
        for (embed in embeds) {
            val known = runCatching { loadExtractor(embed, "$mainUrl/", subtitleCallback, callback) }.getOrDefault(false)
            if (known) { found = true; continue }
            if (genericExtract(embed, callback)) found = true
        }
        return found
    }
}
