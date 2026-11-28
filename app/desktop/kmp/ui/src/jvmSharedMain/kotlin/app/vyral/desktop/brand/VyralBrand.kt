package app.vyral.desktop.brand

enum class VyralLogoStyle(val label: String, val accentArgb: Long) {
    IRIDESCENT_BLUE_PINK("Iridescent", 0xFFB67CFF),
    CHROME_MULTICOLOR("Chrome", 0xFF8EB9FF),
    TEAL_PINK("Teal pink", 0xFF53D9C5),
    BLUE_GOLD("Blue gold", 0xFFE7B75B),
    PSYCHEDELIC_GLOSS("Gloss", 0xFFE879D8),
    PEARLESCENT("Pearl", 0xFFB8C5E8),
    GOLD_SILVER("Gold silver", 0xFFE1C47C),
    SOLID_BLOOD_RED("Blood red", 0xFFEF5350),
    SOLID_COBALT("Cobalt", 0xFF448AFF),
    SOLID_CORAL("Coral", 0xFFFF7A66),
    SOLID_EMERALD("Emerald", 0xFF35C78A),
    SOLID_GOLD("Gold", 0xFFFFCA55),
    SOLID_INDIGO("Indigo", 0xFF766BFF),
    SOLID_LIME("Lime", 0xFFB6D94C),
    SOLID_MAGENTA("Magenta", 0xFFE85CB7),
    SOLID_ORANGE("Orange", 0xFFFF984F),
    SOLID_PURPLE("Purple", 0xFF9C72FF),
    SOLID_ROSE("Rose", 0xFFF1739C),
    SOLID_SKY_BLUE("Sky blue", 0xFF56B8FF),
    SOLID_TURQUOISE("Turquoise", 0xFF3CCFC8),
    SOLID_WHITE("White", 0xFFFFFFFF);

    companion object {
        fun fromStored(value: String?): VyralLogoStyle = entries.firstOrNull { it.name == value } ?: SOLID_WHITE
    }
}

enum class VyralAccentTheme(val label: String) {
    DYNAMIC("Logo"),
    SYSTEM("System"),
    CUSTOM("Custom");

    companion object {
        fun fromStored(value: String?): VyralAccentTheme = entries.firstOrNull { it.name == value } ?: DYNAMIC
    }
}

data class VyralAccentSwatch(val label: String, val hue: Float)

val VyralAccentPalette = listOf(
    VyralAccentSwatch("Rose", 345f),
    VyralAccentSwatch("Orange", 25f),
    VyralAccentSwatch("Gold", 45f),
    VyralAccentSwatch("Lime", 85f),
    VyralAccentSwatch("Emerald", 150f),
    VyralAccentSwatch("Turquoise", 180f),
    VyralAccentSwatch("Sky", 205f),
    VyralAccentSwatch("Cobalt", 220f),
    VyralAccentSwatch("Indigo", 245f),
    VyralAccentSwatch("Violet", 270f),
    VyralAccentSwatch("Magenta", 305f),
)
