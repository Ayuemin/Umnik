package com.ayuemin.ymnik.data

import android.content.Context
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

internal object KnowledgeTextExtractor {
    fun extract(context: Context, file: File, name: String, mimeType: String): List<KnowledgeSourceSection> {
        val lower = name.lowercase()
        val mime = mimeType.lowercase()
        return when {
            lower.endsWith(".pdf") || mime == "application/pdf" -> extractPdf(context, file)
            lower.endsWith(".epub") || mime == "application/epub+zip" -> extractEpub(file)
            lower.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractDocx(file)
            lower.endsWith(".html") || lower.endsWith(".htm") || mime == "text/html" ->
                listOf(KnowledgeSourceSection(htmlToText(file.readText(Charsets.UTF_8))))
            lower.endsWith(".fb2") || mime == "application/x-fictionbook+xml" ->
                listOf(KnowledgeSourceSection(fb2ToText(file.readText(Charsets.UTF_8))))
            lower.endsWith(".xml") || mime.endsWith("xml") ->
                listOf(KnowledgeSourceSection(xmlToText(file.readText(Charsets.UTF_8))))
            isPlainText(lower, mime) -> listOf(KnowledgeSourceSection(readPlainText(file)))
            else -> error("Формат «${name.substringAfterLast('.', name)}» пока нельзя индексировать. Поддерживаются PDF, EPUB, FB2, DOCX, TXT, MD, HTML, XML, JSON, CSV, YAML и файлы исходного кода.")
        }.filter { it.text.isNotBlank() }
    }

    private fun extractPdf(context: Context, file: File): List<KnowledgeSourceSection> {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            (1..document.numberOfPages).mapNotNull { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(document)
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { KnowledgeSourceSection(it, page) }
            }.also { sections ->
                if (sections.isEmpty()) {
                    error("В PDF не найден текст. Возможно, это скан: для него понадобится OCR, который пока не входит в базу знаний.")
                }
            }
        }
    }

    private fun extractEpub(file: File): List<KnowledgeSourceSection> = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { entry ->
                val n = entry.name.lowercase()
                n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".txt")
            }
            .sortedBy { it.name }
            .toList()
        val sections = entries.mapNotNull { entry ->
            val raw = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = if (entry.name.lowercase().endsWith(".txt")) raw else htmlToText(raw)
            text.trim().takeIf { it.isNotBlank() }?.let(::KnowledgeSourceSection)
        }
        if (sections.isEmpty()) error("В EPUB не удалось найти текстовые главы")
        sections
    }

    private fun extractDocx(file: File): List<KnowledgeSourceSection> = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("В DOCX не найден основной документ")
        val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("</w:p>", "</w:p>\n")
            .replace("<w:tab/>", "\t")
        val text = xmlToText(xml)
        if (text.isBlank()) error("В DOCX не найден текст")
        listOf(KnowledgeSourceSection(text))
    }

    private fun htmlToText(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace('\u00A0', ' ')
        .trim()

    internal fun sanitizeFb2Xml(value: String): String = value
        .replace(Regex("""(?is)<binary\b[^>]*>.*?</binary>"""), " ")
        .replace(Regex("""(?is)<stylesheet\b[^>]*>.*?</stylesheet>"""), " ")
        .replace(Regex("""(?i)</(?:p|title|subtitle|section|epigraph|poem|stanza|v|text-author|body)>""")) { match ->
            match.value + "\n"
        }

    private fun fb2ToText(value: String): String {
        val text = xmlToText(sanitizeFb2Xml(value))
        if (text.isBlank()) error("В FB2 не найден читаемый текст")
        return text
    }

    private fun xmlToText(value: String): String = Html.fromHtml(
        value.replace(Regex("<[^>]+>"), " "),
        Html.FROM_HTML_MODE_LEGACY
    ).toString().replace('\u00A0', ' ').trim()

    private fun readPlainText(file: File): String = runCatching { file.readText(Charsets.UTF_8) }
        .getOrElse { file.readText(Charsets.ISO_8859_1) }

    private fun isPlainText(name: String, mime: String): Boolean {
        if (mime.startsWith("text/")) return true
        return listOf(
            ".txt", ".md", ".markdown", ".json", ".csv", ".tsv", ".yaml", ".yml",
            ".kt", ".kts", ".java", ".py", ".js", ".ts", ".css", ".sql", ".log"
        ).any(name::endsWith)
    }
}
