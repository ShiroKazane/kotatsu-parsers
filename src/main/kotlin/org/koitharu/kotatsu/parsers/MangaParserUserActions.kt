package org.koitharu.kotatsu.parsers

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Optional user-specific actions for sources that have their own account system.
 * Implement alongside [MangaParserAuthProvider] to expose actions in the manga menu.
 */
public interface MangaParserUserActions {
    /**
     * Check whether [manga] is stored in the user's remote library.
     * @return true or false, or null if the state cannot be determined
     */
    public suspend fun isBookmarked(manga: Manga): Boolean?

    /**
     * Add or remove [manga] from the user's remote library.
     * @throws AuthRequiredException if the user is not authorized
     */
    public suspend fun setBookmarked(
        manga: Manga,
        bookmarked: Boolean,
    )

    /**
     * Report reading progress to the source. Called fire-and-forget, failures are ignored.
     */
    public suspend fun onChapterRead(
        manga: Manga,
        chapter: MangaChapter,
    ) {
        // no-op by default
    }
}
