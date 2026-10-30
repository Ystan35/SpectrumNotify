package my.spectrum.notify

/**
 * What a material link opens, from its address (Google Drive / Docs, OneDrive / SharePoint, Spectrum, a file
 * name) and a [hint] (the file name, the button label): shown as an icon and a word on its button.
 */
enum class FileKind(val icon: Int, val word: String) {
    DOC(R.drawable.ic_file_doc, "doc"),
    SLIDES(R.drawable.ic_file_slides, "slides"),
    SHEET(R.drawable.ic_file_sheet, "sheet"),
    PDF(R.drawable.ic_file_pdf, "PDF"),
    FOLDER(R.drawable.ic_file_folder, "folder"),
    VIDEO(R.drawable.lk_video, "video");

    /** "Slides", "PDF": for the start of a button label. */
    val title: String get() = word.replaceFirstChar { it.uppercase() }

    companion object {
        private fun ext(s: String, vararg e: String) = e.any { Regex("""\.$it(\b|$)""", RegexOption.IGNORE_CASE).containsMatchIn(s) }

        /** Null when the address and hint do not say (a plain web page, a Spectrum page of unknown type). */
        fun of(url: String, hint: String = ""): FileKind? {
            val u = url.lowercase()
            val h = hint.lowercase()
            return when {
                // by address
                "docs.google.com/presentation" in u || "/:p:/" in u || ext(u, "pptx?", "key", "odp") -> SLIDES
                "docs.google.com/spreadsheets" in u || "/:x:/" in u || ext(u, "xlsx?", "csv", "ods") -> SHEET
                "docs.google.com/document" in u || "/:w:/" in u || ext(u, "docx?", "odt", "rtf") -> DOC
                "/:b:/" in u || ext(u, "pdf") -> PDF
                "drive.google.com/drive/folders" in u || "/:f:/" in u || "/mod/folder/" in u -> FOLDER
                "youtube.com/" in u || "youtu.be/" in u || "vimeo.com/" in u || "/:v:/" in u || ext(u, "mp4", "mov", "webm") -> VIDEO
                // by file name or label ("Week 3.pptx", "application/pdf", "Slides 2")
                ext(h, "pptx?", "key", "odp") || "presentation" in h || Regex("""\bslides?\b""").containsMatchIn(h) -> SLIDES
                ext(h, "xlsx?", "csv", "ods") || "spreadsheet" in h || Regex("""\bsheet\b""").containsMatchIn(h) -> SHEET
                ext(h, "pdf") || "application/pdf" in h || Regex("""\bpdf\b""").containsMatchIn(h) -> PDF
                ext(h, "docx?", "odt", "rtf") || "msword" in h || "wordprocessing" in h || Regex("""\bdoc\b""").containsMatchIn(h) -> DOC
                ext(h, "mp4", "mov", "webm") || "video/" in h || Regex("""\bvideo\b""").containsMatchIn(h) -> VIDEO
                Regex("""\bfolder\b""").containsMatchIn(h) -> FOLDER
                else -> null
            }
        }
    }
}
