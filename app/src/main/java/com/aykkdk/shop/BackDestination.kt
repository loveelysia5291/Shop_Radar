package com.aykkdk.shop

/** Same stacking order as RadarApp; loading never disables navigation. */
internal enum class BackDestination {
    NEW_ITEMS, SUBSCRIPTION_PRODUCTS, SHOP, SEARCH_RESULTS, SEARCH_LOADING, SHOP_LOADING, ROOT;

    companion object {
        fun resolve(newItems: Boolean, subscriptionProducts: Boolean, shop: Boolean,
                    searchResults: Boolean, searchLoading: Boolean, shopLoading: Boolean) = when {
            newItems -> NEW_ITEMS
            subscriptionProducts -> SUBSCRIPTION_PRODUCTS
            shop -> SHOP
            searchResults -> SEARCH_RESULTS
            shopLoading -> SHOP_LOADING
            searchLoading -> SEARCH_LOADING
            else -> ROOT
        }
    }
}
