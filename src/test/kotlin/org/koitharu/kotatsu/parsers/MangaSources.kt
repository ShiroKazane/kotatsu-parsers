package org.koitharu.kotatsu.parsers

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.stream.Stream

/**
 * Parameterizes a test over [MangaParserSource] entries.
 *
 * Set the `PARSERS_TEST_SOURCES` environment variable to a comma separated list of
 * source names (e.g. `DOUJINDESU,KIRYUU,MANGADEX`) to restrict the run, otherwise all
 * sources are tested.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ArgumentsSource(MangaSourcesProvider::class)
internal annotation class MangaSources

internal class MangaSourcesProvider : ArgumentsProvider {
    override fun provideArguments(context: ExtensionContext): Stream<out Arguments> {
        val filter =
            System
                .getenv("PARSERS_TEST_SOURCES")
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()
        val arguments =
            MangaParserSource.entries
                .filter { filter.isEmpty() || it.name in filter }
                .map { Arguments.of(it) }
                .toTypedArray()
        return Stream.of(*arguments)
    }
}
