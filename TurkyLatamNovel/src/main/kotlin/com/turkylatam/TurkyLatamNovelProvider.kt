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

    private val episodeRegex = Regex("""(?i)(?:cap[ií]tulo|capitulo|episodio|ep\.?)[\s\-_]*(\d+)""")
    private val seasonRegex = Regex("""(?i)(?:temporada|season|t)[\s\-_]*(\d+)""")

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document

        // Si se abrió un capítulo, intentar ir a la ficha de la serie.
        if (episodeRegex.containsMatchIn(url)) {
            val seriesLink = doc.select("#body a[href], .breadcrumb a[href]").map { fixUrl(it.attr("href")) }
                .firstOrNull { isContentLink(it) && !episodeRegex.containsMatchIn(it) && it != url }
            if (seriesLink != null) doc = app.get(seriesLink).document
        }

        val title = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: name).substringBefore(" – ").substringBefore(" - Series Turcas").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("#body img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        val tags = doc.select("a[href*=/generos/]").map { it.text() }.filter { it.isNotBlank() }.distinct()
        val year = doc.selectFirst("a[href*=/ano/]")?.text()?.toIntOrNull()

        val episodes = LinkedHashMap<String, Episode>()
        for (a in doc.select("#body a[href]")) {
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith(mainUrl)) continue
            val text = a.text()
            val epMatch = episodeRegex.find(text) ?: episodeRegex.find(href) ?: continue
            if (episodes.containsKey(href)) continue
            val season = (seasonRegex.find(text) ?: seasonRegex.find(href))?.groupValues?.get(1)?.toIntOrNull()
            episodes[href] = newEpisode(href) {
                this.name = text.ifBlank { "Capítulo ${epMatch.groupValues[1]}" }
                this.episode = epMatch.groupValues[1].toIntOrNull()
                this.season = season ?: 1
            }
        }
        val list = episodes.values.sortedWith(compareBy({ it.season }, { it.episode }))
            .ifEmpty { listOf(newEpisode(url) { this.name = title; this.episode = 1; this.season = 1 }) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, list) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    // ---------- Enlaces de vídeo ----------

    private val urlInText = Regex("""https?://[^\s"'<>\\]+""")

    private fun tryBase64Url(value: String): String? {
        if (value.length < 16 || !value.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return null
        return runCatching { base64Decode(value) }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun collectPlayerUrls(doc: Document): Set<String> {
        val out = LinkedHashSet<String>()
        val attrs = listOf("src", "data-src", "data-url", "data-video", "data-player", "data-link", "data-embed", "data-lazy-src")
        doc.select("iframe, [data-src], [data-url], [data-video], [data-player], [data-link], [data-embed]").forEach { el ->
            attrs.forEach { at ->
                val v = el.attr(at).trim()
                if (v.isBlank()) return@forEach
                val u = if (v.startsWith("http") || v.startsWith("//")) fixUrl(v) else tryBase64Url(v)
                if (u != null && !u.contains("googletagmanager") && !u.endsWith(".jpg") && !u.endsWith(".png") && !u.endsWith(".webp")) out.add(u)
            }
        }
        // URLs dentro de scripts del reproductor (p. ej. listas de servidores).
        doc.select("script").forEach { s ->
            val data = s.data()
            if (data.contains("iframe", true) || data.contains("embed", true) || data.contains("player", true)) {
                urlInText.findAll(data.replace("\\/", "/")).map { it.value }
                    .filter { it.contains("embed") || it.contains("/e/") || it.contains("/v/") || it.contains(".m3u8") || it.contains("player") }
                    .forEach { out.add(it) }
            }
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        val urls = collectPlayerUrls(doc)
        for (u in urls) {
            // Reproductor alojado en la propia web: abrirlo y sacar el servidor real.
            val targets = if (u.startsWith(mainUrl)) {
                runCatching { collectPlayerUrls(app.get(u, referer = data).document) }.getOrDefault(emptySet())
            } else setOf(u)
            for (t in targets) {
                if (loadExtractor(t, data, subtitleCallback, callback)) found = true
            }
        }
        return found
    }
}
