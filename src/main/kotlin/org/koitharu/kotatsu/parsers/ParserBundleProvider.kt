package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.MangaParserSource

public interface ParserBundleProvider {
    public fun sources(): Array<MangaParserSource>

    public fun create(
        context: MangaLoaderContext,
        sourceName: String,
    ): MangaParser
}
