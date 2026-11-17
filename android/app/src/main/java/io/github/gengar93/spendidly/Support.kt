package io.github.gengar93.spendidly

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

const val SUPPORT_EMAIL = "aditya.janaswamy+spendidly@gmail.com"

const val PRIVACY_POLICY_URL = "https://gengar93.github.io/spendidly/privacy"

/** "1.0 (1)", from the installed package. */
fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    "${info.versionName} (${info.versionCode})"
}.getOrDefault("unknown")

/**
 * Opens the user's email app with a draft reporting an AI reply. Nothing is sent until they send it.
 * Returns false when no email app can take it.
 */
fun reportReply(context: Context, question: String, reply: String, model: String?): Boolean {
    val subject = "Spendidly: reported AI reply"
    val body = buildString {
        appendLine("What's wrong with this reply? (optional)")
        appendLine()
        appendLine()
        appendLine("--- Reply ---")
        appendLine(reply.trim())
        appendLine()
        appendLine("--- Question ---")
        appendLine(question.trim())
        appendLine()
        appendLine("App: Spendidly ${appVersion(context)}")
        appendLine("Model: ${model ?: "unknown"}")
        appendLine()
        append("You can edit or remove anything here before sending. Nothing is sent until you send it.")
    }
    // Both the mailto query and the extras, since email apps differ in which they read.
    val uri = Uri.parse("mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
    val intent = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Opens the privacy policy in the browser. Returns false when nothing can open it. */
fun openPrivacyPolicy(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
    true
} catch (_: ActivityNotFoundException) {
    false
}
