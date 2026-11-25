package com.licht.nuvx.data.settings

internal fun migratedSpotifySpdcToken(secureValue: String?, legacyValue: String?): String =
    secureValue?.takeIf(String::isNotBlank) ?: legacyValue.orEmpty()
