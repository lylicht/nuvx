// SPDX-License-Identifier: AGPL-3.0-or-later

package com.licht.nuvx.data.innertube

import com.licht.nuvx.auth.normalizeDataSyncId
import com.licht.nuvx.data.model.Account
import com.licht.nuvx.data.model.AccountChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object InnertubeAccountParser {
    fun parseAccount(response: JsonElement): Account? {
        val header = InnertubeParser.collectRenderers(response, "activeAccountHeaderRenderer").firstOrNull()
            ?: return null
        val email = header.o("email").runs()
            .ifBlank { header.o("email").s("simpleText").orEmpty() }
            .ifBlank { header.o("channelHandle").runs() }
        val name = header.o("accountName").runs()
            .takeIf(::hasVisibleText)
            ?: email.takeIf(::hasVisibleText)
            ?: "YouTube Music"
        return Account(
            name = name,
            email = email,
            thumbnailUrl = header.o("accountPhoto").a("thumbnails").best(),
        )
    }

    fun parseAccountChannels(root: JsonElement): List<AccountChannel> =
        InnertubeParser.collectRenderers(root, "accountItem").mapNotNull { item ->
            val accountName = item.o("accountName").runs()
                .ifBlank { item.o("accountName").s("simpleText").orEmpty() }
            val pageId = item.findString("pageId")
            val dataSyncId = normalizeDataSyncId(item.findString("datasyncIdToken"))
            if (pageId == null && dataSyncId == null) return@mapNotNull null
            val handle = item.o("channelHandle").runs()
                .ifBlank { item.o("channelHandle").s("simpleText").orEmpty() }
                .ifBlank { item.o("accountByline").runs() }
                .ifBlank { item.o("accountByline").s("simpleText").orEmpty() }
            val name = accountName.takeIf(::hasVisibleText)
                ?: handle.takeIf(::hasVisibleText)
                ?: "YouTube Music"
            AccountChannel(
                name = name,
                subtitle = handle,
                thumbnailUrl = item.o("accountPhoto").a("thumbnails").best(),
                pageId = pageId,
                dataSyncId = dataSyncId,
                activeOnWeb = (item["isSelected"] as? JsonPrimitive)?.content == "true",
            )
        }.distinctBy { it.key }

    private fun hasVisibleText(value: String): Boolean = value.any {
        !it.isWhitespace() && !it.isISOControl() && it != '\u3164' && it != '\uFFA0' &&
            Character.getType(it) != Character.FORMAT.toInt()
    }
}
