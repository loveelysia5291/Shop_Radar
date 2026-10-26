package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class FacebookFiltersTest {
    @Test fun suppliedPublicSortValuesAreSupported() {
        for (sort in listOf("creation_time_descend", "price_descend", "price_ascend")) {
            assertEquals(sort, FacebookFilters.sort(sort))
        }
        assertEquals("最新", FacebookFilters.sorts.first { it.first == "creation_time_descend" }.second)
    }

    @Test fun existingDefaultAndUnknownValuesRemainSafe() {
        assertEquals("distance_ascend", FacebookFilters.DEFAULT_SORT)
        assertEquals(FacebookFilters.DEFAULT_SORT, FacebookFilters.sort("distance_ascend"))
        assertEquals(FacebookFilters.DEFAULT_SORT, FacebookFilters.sort(""))
        assertEquals(FacebookFilters.DEFAULT_SORT, FacebookFilters.sort("price_descend&radius=999"))
        assertEquals(4, FacebookFilters.sorts.map { it.first }.distinct().size)
    }
}
