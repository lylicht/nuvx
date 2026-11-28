package app.vyral.night.data.listentogether

/** Invite formatting shared by the KMP desktop UI. */
object JamInvite {
    private val schemeInvite = Regex("""^(?:vyral|bitchord)://party/?([A-Za-z0-9]{6})(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val webInvite = Regex("""^https?://[^/]+/invite/([A-Za-z0-9]{6})(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val code = Regex("""^[A-Za-z0-9]{6}$""")

    fun parse(value: String): String? {
        val text = value.trim()
        val match = listOf(schemeInvite, webInvite, code)
            .firstNotNullOfOrNull { regex -> regex.matchEntire(text) }
        return match?.groupValues?.getOrNull(1)?.uppercase()
            ?: text.takeIf(code::matches)?.uppercase()
    }

    fun url(code: String, server: String? = null): String {
        val normalized = code.uppercase()
        val base = server?.trim()?.trimEnd('/')
        return if (base.isNullOrBlank()) "vyral://party/$normalized" else "$base/invite/$normalized"
    }
}
