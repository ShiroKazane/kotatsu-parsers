package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.ParserSource

public interface ParserBundleProvider {
    public fun sources(): Array<ParserSource>

    public fun create(
        context: MangaLoaderContext,
        sourceName: String,
    ): MangaParser
}
