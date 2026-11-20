package org.koitharu.kotatsu.parsers.model

/**
 * Immutable description of a manga source.
 *
 * Unlike the generated [MangaParserSource] constants, instances of this class can be
 * produced at runtime (see [org.koitharu.kotatsu.parsers.ParserBundleProvider]), which
 * allows new sources to be delivered with a parser bundle without rebuilding the host
 * application.
 */
public data class ParserSource(
    override val name: String,
    public val title: String,
    public val locale: String,
    public val contentType: ContentType,
    public val isBroken: Boolean = false,
) : MangaSource
