package app.remotely.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Genre texts as Zala sends them (31.10.2026). */
class GenresTest {
    @Test fun groupsOfRealGenres() {
        assertEquals(GenreGroup.MOVIES, Genres.of("Драма, Криминал, Детектив"))
        assertEquals(GenreGroup.MOVIES, Genres.of("Комедия"))
        assertEquals(GenreGroup.NEWS, Genres.of("Информация, Аналитика, Обзор, "))
        assertEquals(GenreGroup.LEARNING, Genres.of("Религия"))
        assertEquals(GenreGroup.SHOWS, Genres.of("Кулинария, Развлечения"))
        assertEquals(GenreGroup.MUSIC, Genres.of("Концерт, Культура, Развлечения"))
        assertEquals(GenreGroup.OTHER, Genres.of("Интим"))
    }

    @Test fun mixedGenresTakeTheFirstGroupInOrder() {
        // A cartoon comedy is children's, a sports review is sport.
        assertEquals(GenreGroup.KIDS, Genres.of("Анимация, Комедия, Приключения, Семейный"))
        assertEquals(GenreGroup.SPORT, Genres.of("Информация, Обзор, Спорт, Аналитика, Репортаж"))
        assertEquals(GenreGroup.MUSIC, Genres.of("Культура, Информация, Общество, Шоу, Музыка"))
    }

    @Test fun breaksInBroadcastingAreNotMarked() {
        assertNull(Genres.of("Трансляция", "Перерыв в вещании"))
        assertNull(Genres.of("Информация", "Перерыв вещания"))
        assertNull(Genres.of("Трансляция", "Перапынак ў вяшчанні (6+)"))
        assertEquals(GenreGroup.MOVIES, Genres.of("Короткометражка, Комедия", "Х/ф \"Перерыв\""))
    }

    @Test fun noGenreNoMark() {
        assertNull(Genres.of(""))
        assertNull(Genres.of(null))
        assertNull(Genres.of(" , "))
    }
}
