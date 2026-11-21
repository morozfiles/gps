package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class RecentSearchesTest {
    @Test fun storesAtMostFiveNewestQueries() {
        val list=(1..8).fold(emptyList<String>()){acc,i->
            RecentSearches.add(acc,"адрес $i")
        }
        assertEquals(5,list.size)
        assertEquals(listOf("адрес 8","адрес 7","адрес 6","адрес 5","адрес 4"),list)
    }

    @Test fun repeatedPlaceMovesToFrontWithoutDuplicate() {
        val original=listOf("Киев, Леси Украинки, 10","Одесса","Львов")
        val result=RecentSearches.add(original,"  киев,   леси украинки, 10  ")
        assertEquals(3,result.size)
        assertEquals("киев, леси украинки, 10",result[0])
        assertEquals("Одесса",result[1])
    }

    @Test fun blankQueryCannotEraseSearchHistory() {
        val original=listOf("Дом", "Киев")
        assertEquals(original,RecentSearches.add(original,"   "))
    }

    @Test fun capsVeryLongSearchString() {
        val result=RecentSearches.add(emptyList(),"a".repeat(500))
        assertEquals(300,result.single().length)
    }
}
