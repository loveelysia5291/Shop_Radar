package com.aykkdk.shop

internal data class CarousellCategory(val name: String, val url: String, val children: List<CarousellCategory> = emptyList())

/** Public Carousell Taiwan category paths supplied for the two-level browser. */
internal object CarousellCategories {
    // Category browsing is always newest-first.  The supplied URLs also carry
    // short-lived analytics parameters; omit those so saved selections stay
    // clean and continue working after the original browsing session expires.
    private fun url(path: String) = "https://tw.carousell.com/categories/$path/?sort_by=3"
    private fun child(name: String, path: String) = CarousellCategory(name, url(path))
    val groups = listOf(
        CarousellCategory("玩具收藏品", url("toys-collectibles-12"), listOf(
            child("集換式卡牌", "toys-collectibles-12/trading-cards-11001"), child("公仔與模型", "toys-collectibles-12/figurines-models-11002"),
            child("玩具", "toys-collectibles-12/toys-11003"), child("偶像周邊", "toys-collectibles-12/fan-merchandise-6691"), child("紀念品與古董", "toys-collectibles-12/memorabilia-antiques-9")
        )),
        CarousellCategory("免費物品", url("free-items-2158")),
        CarousellCategory("電腦及科技產品", url("computers-tech-1094"), listOf(
            child("電腦", "computers-tech-1094/desktops-6391"), child("桌上電腦或筆記型電腦", "computers-tech-1094/laptops-notebooks-6392"),
            child("電腦周邊產品", "computers-tech-1094/parts-accessories-1095"), child("印表機及影印機", "computers-tech-1094/printers-scanners-copiers-6404"), child("商務用科技產品", "computers-tech-1094/office-business-technology-6405")
        )),
        CarousellCategory("手機及配件", url("mobile-phones-gadgets-1091"), listOf(
            child("手機", "mobile-phones-gadgets-1091/mobile-phones-6406"), child("平板電腦", "mobile-phones-gadgets-1091/tablets-1245"), child("電子書閱讀器", "mobile-phones-gadgets-1091/e-readers-6435"),
            child("智慧穿戴裝置及智慧手錶", "mobile-phones-gadgets-1091/wearables-smart-watches-6436"), child("電子周邊配件及產品", "mobile-phones-gadgets-1091/mobile-gadget-accessories-1093"), child("對講機", "mobile-phones-gadgets-1091/walkie-talkie-6444"), child("其他", "mobile-phones-gadgets-1091/other-gadgets-1246")
        )),
        CarousellCategory("她的時尚", url("women-s-fashion-4"), listOf(
            child("休閒運動服飾", "women-s-fashion-4/activewear-1085"), child("孕婦衣服", "women-s-fashion-4/maternity-wear-6561"), child("上衣", "women-s-fashion-4/womens-top-1055"), child("褲＆裙", "women-s-fashion-4/womens-bottom-1056"), child("洋裝＆套裝", "women-s-fashion-4/dresses-sets-1058"), child("鞋類", "women-s-fashion-4/footwear-1061"), child("泳衣、潛水衣", "women-s-fashion-4/swimwear-6585"), child("外套、夾克、大衣", "women-s-fashion-4/coats-jackets-outerwear-1059"), child("包包、錢包", "women-s-fashion-4/bags-and-wallets-1060"), child("珠寶、飾品與收納", "women-s-fashion-4/jewelry-organizers-6596"), child("手錶及配件", "women-s-fashion-4/watches-accessories-1062"), child("內衣褲、小可愛、睡衣", "women-s-fashion-4/new-undergarments-loungewear-6616")
        )),
        CarousellCategory("他的時尚", url("men-s-fashion-3"), listOf(
            child("休閒運動服飾", "men-s-fashion-3/activewear-1084"), child("上衣與西裝", "men-s-fashion-3/tops-sets-1078"), child("褲子", "men-s-fashion-3/bottoms-1079"), child("鞋類", "men-s-fashion-3/footwear-1082"), child("外套、夾克、大衣", "men-s-fashion-3/coats-jackets-outerwear-1080"), child("包包與錢包", "men-s-fashion-3/bags-1081"), child("手錶及配件", "men-s-fashion-3/watches-accessories-6642")
        )),
        CarousellCategory("香水、美妝、保養", url("beauty-personal-care-11"), listOf(
            child("個人消毒用品", "beauty-personal-care-11/sanitizers-disinfectants-6693"), child("美甲、手部保養", "beauty-personal-care-11/hands-nails-1065"), child("耳朵護理", "beauty-personal-care-11/ear-care-6694"), child("眼部彩妝、保養", "beauty-personal-care-11/vision-care-6695"), child("足、腿部保養", "beauty-personal-care-11/foot-care-6696"), child("唇部彩妝、保養", "beauty-personal-care-11/oral-care-6697"), child("個人清潔保養", "beauty-personal-care-11/sanitary-hygiene-6698"), child("香水、體香劑", "beauty-personal-care-11/fragrance-deodorants-1068"), child("身體清潔保養", "beauty-personal-care-11/bath-body-1066"), child("臉部彩妝、保養與清潔", "beauty-personal-care-11/face-6702"), child("美髮清潔與護理", "beauty-personal-care-11/hair-1067"), child("男士美容保養", "beauty-personal-care-11/mens-grooming-1064")
        )),
        CarousellCategory("名牌精品", url("luxury-20"), listOf(
            child("精品包與皮夾", "luxury-20/bags-and-wallets-1156"), child("精品服飾", "luxury-20/apparel-1158"), child("精品配件", "luxury-20/accessories-1155"), child("精品手錶", "luxury-20/watches-1157"), child("精品鞋款", "luxury-20/sneakers-footwear-6735")
        )),
        CarousellCategory("電玩遊戲相關", url("video-gaming-1189"), listOf(
            child("電玩主機", "video-gaming-1189/video-games-consoles-1109"), child("電玩、電腦遊戲軟體", "video-gaming-1189/video-games-1110"), child("主機配件與周邊商品", "video-gaming-1189/gaming-accessories-1111")
        )),
        CarousellCategory("耳機及錄音音訊設備", url("audio-1600"), listOf(
            child("耳機", "audio-1600/earphones-6460"), child("頭戴式耳機", "audio-1600/headphones-headsets-6461"), child("麥克風", "audio-1600/microphones-6462"), child("錄音機", "audio-1600/voice-recorders-6463"), child("音樂播放裝置 MP3及CD 播放器", "audio-1600/portable-music-players-6464"), child("可攜式音響設備", "audio-1600/portable-audio-accessories-6465"), child("Soundbar、揚聲器、藍牙喇叭、耳擴", "audio-1600/soundbars-speakers-amplifiers-6466"), child("其他音響配件及設備", "audio-1600/other-audio-equipment-6467")
        )),
        CarousellCategory("相機與攝影", url("photography-6"), listOf(
            child("相機", "photography-6/cameras-1236"), child("鏡頭及裝備", "photography-6/lens-kits-6468"), child("空拍機", "photography-6/drones-6469"), child("攝影機", "photography-6/video-cameras-6470"), child("攝影配件", "photography-6/photography-accessories-1240")
        )),
        CarousellCategory("家具及居家用品", url("furniture-home-living-13"), listOf(
            child("家具", "furniture-home-living-13/furniture-1099"), child("戶外家具", "furniture-home-living-13/outdoor-furniture-6481"), child("燈飾及風扇", "furniture-home-living-13/lighting-fans-6482"), child("居家裝飾", "furniture-home-living-13/home-decor-1100"), child("居家香薰", "furniture-home-living-13/home-fragrance-6495"), child("床具浴巾", "furniture-home-living-13/bedding-towels-6496"), child("浴室、廚房用品配件", "furniture-home-living-13/bathroom-kitchen-fixtures-6497"), child("居家改善及收納用品", "furniture-home-living-13/home-improvement-organization-1102"), child("居家清潔及護理用品", "furniture-home-living-13/cleaning-homecare-supplies-6504"), child("廚具和餐具", "furniture-home-living-13/kitchenware-tableware-6510"), child("安全及門鎖", "furniture-home-living-13/security-locks-6521"), child("園藝", "furniture-home-living-13/gardening-1103")
        )),
        CarousellCategory("電視及其他電器", url("tv-home-appliances-30"), listOf(
            child("電視及其他電器", "tv-home-appliances-30/tv-entertainment-6532"), child("廚房用品", "tv-home-appliances-30/kitchen-appliances-1597"), child("冷氣機和電暖器", "tv-home-appliances-30/air-conditioners-heating-6538"), child("洗衣機及乾衣機", "tv-home-appliances-30/washing-machines-dryers-6555"), child("吸塵機及家居清潔用品", "tv-home-appliances-30/vacuum-cleaner-housekeeping-6556"), child("熱水器及淋浴設備", "tv-home-appliances-30/water-heater-instant-showers-6557"), child("空氣清淨機及除濕機", "tv-home-appliances-30/air-purifiers-dehumidifiers-6558"), child("轉換器及插頭", "tv-home-appliances-30/electrical-adaptors-sockets-6559"), child("熨斗及掛熨機", "tv-home-appliances-30/irons-steamers-6560"), child("其他家庭電器", "tv-home-appliances-30/other-home-appliances-1598")
        )),
        CarousellCategory("嬰兒及兒童", url("babies-kids-19"), listOf(
            child("嬰兒及兒童流行時尚", "babies-kids-19/babies-kids-fashion-1071"), child("兒童傢具", "babies-kids-19/baby-nursery-kids-furniture-6656"), child("外出用品", "babies-kids-19/going-out-6666"), child("洗澡及尿布", "babies-kids-19/bathing-changing-6671"), child("護理及哺育用品", "babies-kids-19/nursing-and-feeding-1077"), child("嬰兒監視器", "babies-kids-19/baby-monitors-6682"), child("孕婦用品", "babies-kids-19/maternity-care-1076"), child("嬰兒玩具", "babies-kids-19/infant-playtime-1072")
        )),
        CarousellCategory("興趣與休閒", url("hobbies-lifestyle-6683"), listOf(
            child("樂器、音樂相關", "hobbies-lifestyle-6683/music-media-14"), child("書本及雜誌", "hobbies-lifestyle-6683/books-magazines-5"), child("文具、藝術、手工藝", "hobbies-lifestyle-6683/stationery-craft-8"), child("宗教相關物品", "hobbies-lifestyle-6683/religious-items-1159"), child("旅遊相關用品", "hobbies-lifestyle-6683/travel-1957")
        )),
        CarousellCategory("健康及營養品", url("health-nutrition-6704"), listOf(
            child("殺蟲劑", "health-nutrition-6704/insect-repellents-6705"), child("按摩紓緩用品", "health-nutrition-6704/massage-devices-6706"), child("輔具/護具", "health-nutrition-6704/assistive-rehabilatory-aids-6707"), child("口罩、面罩", "health-nutrition-6704/non-surgical-face-masks-face-shields-6709"), child("保健食品", "health-nutrition-6704/health-supplements-6710")
        )),
        CarousellCategory("體育器材", url("sports-equipment-10"), listOf(
            child("運動與比賽用品", "sports-equipment-10/sports-games-6714"), child("健身用品", "sports-equipment-10/exercise-and-fitness-1089"), child("自行車及配件", "sports-equipment-10/bicycles-parts-6726"), child("釣魚", "sports-equipment-10/fishing-6727"), child("健行及露營", "sports-equipment-10/hiking-camping-1959"), child("其他運動配件", "sports-equipment-10/other-sports-equipment-supplies-1090")
        )),
        CarousellCategory("食物和飲料", url("food-drinks-103"), listOf(
            child("飯麵", "food-drinks-103/rice-noodles-1153"), child("手作食品", "food-drinks-103/homemade-bakes-6728"), child("飲料", "food-drinks-103/beverages-1217"), child("罐裝食品&即食食品", "food-drinks-103/packaged-instant-food-1150"), child("新鮮食品", "food-drinks-103/fresh-produce-6729"), child("調味料", "food-drinks-103/spice-seasoning-6730"), child("冰凍食品", "food-drinks-103/chilled-frozen-food-6731"), child("禮品籃和禮籃", "food-drinks-103/gift-baskets-hampers-6732"), child("其他食物及飲料", "food-drinks-103/other-food-drinks-6733")
        )),
        CarousellCategory("寵物用品", url("pet-supplies-29"), listOf(
            child("寵物及其他", "pet-supplies-29/homes-other-pet-accessories-1107"), child("寵物食品", "pet-supplies-29/pet-food-1108"), child("寵物健康與美容", "pet-supplies-29/health-grooming-1106")
        )),
        CarousellCategory("門票＆禮券", url("tickets-vouchers-18"), listOf(
            child("當地景點門票及交通", "tickets-vouchers-18/local-attractions-transport-1118"), child("優惠券", "tickets-vouchers-18/vouchers-1120"), child("活動門票", "tickets-vouchers-18/events-tickets-1121"), child("商店或商場現金券", "tickets-vouchers-18/store-credits-6734"), child("機票及海外景點", "tickets-vouchers-18/flights-overseas-attractions-1956")
        )),
        CarousellCategory("賣屋租屋", url("property-102"), listOf(
            child("房屋出售", "property-102/for-sale-1170"), child("房屋出租", "property-102/property-rentals-1218"), child("其他", "property-102/housing-others-1171")
        )),
        CarousellCategory("機車", url("motorbikes-108"), listOf(
            child("新車", "motorbikes-108/brand-new-1611"), child("新古保固", "motorbikes-108/second-hand-in-warranty-1610"), child("二手機車", "motorbikes-108/preowned-1612"), child("電動機車", "motorbikes-108/electric-scooter-1615"), child("重機", "motorbikes-108/heavy-motorcycle-1613")
        )),
        CarousellCategory("汽車", url("cars-32"), listOf(
            child("汽車出售", "cars-32/cars-for-sale-1164"), child("租車", "cars-32/vehicle-rentals-1167")
        )),
        CarousellCategory("汽機車零配件", url("auto-accessories-109")),
        CarousellCategory("服務類別", url("services-1570"), listOf(
            child("居家服務", "services-1570/home-services-1571"), child("家電維修", "services-1570/electronics-gadget-repairs-1579"), child("補習諮詢", "services-1570/tuition-1580"), child("美容服務", "services-1570/beauty-services-1581"), child("其他", "services-1570/others-1582")
        )),
        CarousellCategory("工作", url("jobs-1544"), listOf(
            child("兼職", "jobs-1544/part-time-1545"), child("全職", "jobs-1544/full-time-1557"), child("實習和其他", "jobs-1544/internships-others-1569")
        )),
        CarousellCategory("哩哩扣扣", url("everything-else-16"), listOf(
            child("其他", "everything-else-16/others-1163")
        )),
        CarousellCategory("預購", url("preorders-67")),
        CarousellCategory("徵收好物", url("looking-for-21")),
        CarousellCategory("社群活動", url("community-26"))
    )
}
