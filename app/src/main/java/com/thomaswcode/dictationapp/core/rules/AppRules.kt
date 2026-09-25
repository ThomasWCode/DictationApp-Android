package com.thomaswcode.dictationapp.core.rules

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.insertion.ForegroundContext
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import kotlinx.serialization.Serializable

/**
 * Per-application defaults. Either [packageGlob] (matched against the Android package name,
 * case-insensitive, `*` and `?` wildcards) or [urlHost] (matched against the browser tab host, suffix
 * match on a label boundary so `google.com` matches `mail.google.com`) must be set. Null fields inherit.
 */
@Serializable
data class AppRule(
    val packageGlob: String? = null,
    val urlHost: String? = null,
    val tone: Tone? = null,
    val level: CleanupLevel? = null,
    val insertMethod: InsertMethod? = null,
    /** Free-text hint passed to the LLM, e.g. "This is a chat message." */
    val hint: String? = null,
    val enabled: Boolean = true,
    /** Friendly name shown in the rules list (the app label when picked from the app list). */
    val label: String? = null,
) {
    val displayTarget: String get() = if (!urlHost.isNullOrBlank()) urlHost else packageGlob.orEmpty()
}

/** The effective style for one dictation. [matchedRule] is the url rule, else the package rule, else null. */
data class ResolvedRule(
    val tone: Tone,
    val level: CleanupLevel,
    val insertMethod: InsertMethod,
    val hint: String?,
    val matchedBy: String,
    val matchedRule: AppRule? = null,
)

/** Pure. URL rule beats package rule beats defaults; within a kind, the first matching rule wins. */
object AppRulesResolver {
    fun resolve(
        context: ForegroundContext,
        rules: List<AppRule>,
        defaultTone: Tone,
        defaultLevel: CleanupLevel,
        defaultInsert: InsertMethod,
    ): ResolvedRule {
        var urlRule: AppRule? = null
        var packageRule: AppRule? = null
        val host = context.urlHost
        for (rule in rules) {
            if (!rule.enabled) continue
            if (urlRule == null && host != null && !rule.urlHost.isNullOrBlank() && hostMatches(host, rule.urlHost)) {
                urlRule = rule
            }

            if (packageRule == null && !rule.packageGlob.isNullOrBlank() && globMatches(context.packageName, rule.packageGlob)) {
                packageRule = rule
            }
        }

        // Layer: defaults <- package rule <- url rule. Unset fields fall through.
        val tone = urlRule?.tone ?: packageRule?.tone ?: defaultTone
        val level = urlRule?.level ?: packageRule?.level ?: defaultLevel
        val insert = urlRule?.insertMethod ?: packageRule?.insertMethod ?: defaultInsert
        val hint = urlRule?.hint ?: packageRule?.hint
        val matchedBy = when {
            urlRule != null -> "url:${urlRule.urlHost}"
            packageRule != null -> "package:${packageRule.packageGlob}"
            else -> "default"
        }
        return ResolvedRule(tone, level, insert, hint, matchedBy, urlRule ?: packageRule)
    }

    /** `mail.google.com` matches rule host `mail.google.com` or `google.com` (suffix on a label boundary). */
    fun hostMatches(host: String, ruleHost: String): Boolean {
        val h = host.trim().lowercase()
        val r = ruleHost.trim().lowercase().trimStart('.')
        if (h.isEmpty() || r.isEmpty()) return false
        return h == r || h.endsWith(".$r")
    }

    fun globMatches(packageName: String, glob: String): Boolean {
        if (packageName.isEmpty()) return false
        val pattern = buildString {
            append('^')
            for (ch in glob.trim()) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }

            append('$')
        }
        return Regex(pattern, RegexOption.IGNORE_CASE).matches(packageName)
    }
}

/**
 * The rules version 0.1.0 seeded into every new settings file. The list now starts empty and every app follows
 * the Style defaults; these are kept only so the settings migration can recognise and remove them.
 */
object LegacyAppRules {
    private const val EMAIL = "This is an email."
    private const val CHAT = "This is a chat message."
    private const val DOCUMENT = "This is a document."

    /** True when [rule] targets an app or host that was seeded, whatever its style is now. */
    fun isSeededTarget(rule: AppRule): Boolean = seed().any { sameTarget(it, rule) }

    /**
     * True when [rule] is still one of the seeded rules: same target, insertion method, hint and on/off state (tone,
     * level and label may differ). A seeded-target rule whose insertion method, hint or switch the user changed was
     * customised and is kept by the migration.
     */
    fun isUnmodifiedSeed(rule: AppRule): Boolean = seed().any { seed ->
        sameTarget(seed, rule) && seed.insertMethod == rule.insertMethod && seed.hint?.trim() == rule.hint?.trim() && seed.enabled == rule.enabled
    }

    private fun sameTarget(seed: AppRule, rule: AppRule): Boolean =
        seed.packageGlob?.trim().equals(rule.packageGlob?.trim(), ignoreCase = true) &&
            seed.urlHost?.trim().equals(rule.urlHost?.trim(), ignoreCase = true)

    fun seed(): List<AppRule> = listOf(
        // Rich-text editors lose formatting (links, signatures) when their whole text is replaced, so they paste.
        AppRule(packageGlob = "com.google.android.gm", label = "Gmail", tone = Tone.Formal, level = CleanupLevel.Medium, insertMethod = InsertMethod.Paste, hint = EMAIL),
        AppRule(packageGlob = "com.microsoft.office.outlook", label = "Outlook", tone = Tone.Formal, level = CleanupLevel.Medium, insertMethod = InsertMethod.Paste, hint = EMAIL),
        AppRule(urlHost = "mail.google.com", tone = Tone.Formal, level = CleanupLevel.Medium, hint = EMAIL),
        AppRule(urlHost = "outlook.live.com", tone = Tone.Formal, level = CleanupLevel.Medium, hint = EMAIL),
        AppRule(urlHost = "outlook.office.com", tone = Tone.Formal, level = CleanupLevel.Medium, hint = EMAIL),
        AppRule(packageGlob = "com.whatsapp", label = "WhatsApp", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.whatsapp.w4b", label = "WhatsApp Business", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.microsoft.teams", label = "Teams", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.Slack", label = "Slack", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.google.android.apps.messaging", label = "Messages", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.samsung.android.messaging", label = "Samsung Messages", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "org.telegram.messenger", label = "Telegram", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "org.thoughtcrime.securesms", label = "Signal", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.facebook.orca", label = "Messenger", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(urlHost = "web.whatsapp.com", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(urlHost = "teams.microsoft.com", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(urlHost = "slack.com", tone = Tone.Casual, level = CleanupLevel.Light, hint = CHAT),
        AppRule(packageGlob = "com.microsoft.office.word", label = "Word", tone = Tone.Formal, level = CleanupLevel.Medium, insertMethod = InsertMethod.Paste, hint = DOCUMENT),
        AppRule(packageGlob = "com.google.android.apps.docs.editors.docs", label = "Google Docs", tone = Tone.Formal, level = CleanupLevel.Medium, insertMethod = InsertMethod.Paste, hint = DOCUMENT),
        AppRule(urlHost = "docs.google.com", tone = Tone.Formal, level = CleanupLevel.Medium, hint = DOCUMENT),
        AppRule(packageGlob = "com.termux", label = "Termux", tone = Tone.Neutral, level = CleanupLevel.None, hint = "This is a terminal."),
    )
}
