/*
 * SPDX-FileCopyrightText: 2017-2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import android.content.Context
import androidx.preference.PreferenceManager
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.ktx.getStringSafe

object FilenameUtils {
    private const val CHARSET_MOST_SPECIAL = "[\\n\\r|?*<\":\\\\>/']+"
    private const val CHARSET_ONLY_LETTERS_AND_DIGITS = "[^\\w\\d]+"

    /**
     * #143 #44 #42 #22: make sure that the filename does not contain illegal chars.
     *
     * @param context the context to retrieve strings and preferences from
     * @param title the title to create a filename from
     * @return the filename
     */
    @JvmStatic
    fun createFilename(context: Context, title: String): String {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        val charsetLd = context.getString(R.string.charset_letters_and_digits_value)
        val charsetMs = context.getString(R.string.charset_most_special_value)
        val defaultCharset = context.getString(R.string.default_file_charset_value)

        val replacementChar = sharedPreferences.getStringSafe(
            context.getString(R.string.settings_file_replacement_character_key),
            "_"
        )
        val selectedCharset = sharedPreferences.getStringSafe(
            context.getString(R.string.settings_file_charset_key),
            ""
        ).ifEmpty { defaultCharset }

        val charset = when (selectedCharset) {
            charsetLd -> CHARSET_ONLY_LETTERS_AND_DIGITS
            charsetMs -> CHARSET_MOST_SPECIAL
            else -> selectedCharset // Is the user using a custom charset?
        }

        return createFilename(title, charset, Matcher.quoteReplacement(replacementChar))
    }

    /**
     * Build a filename for the given [info] by applying the user-configured prefix/suffix
     * templates around the (sanitized) stream title, then sanitizing the whole result.
     *
     * Supported placeholders in the prefix/suffix templates:
     *  - `{title}`        - stream title
     *  - `{uploader}`     - uploader / channel name
     *  - `{upload_date}`  - upload date formatted as `yyyy-MM-dd` (empty if unknown)
     *  - `{quality}`      - the resolved stream quality (e.g. `1080p`, `128kbps`); empty if null
     *
     * If both prefix and suffix preferences are empty, the result is identical to
     * `createFilename(context, info.name)`.
     *
     * @param context  context to read preferences and resources from
     * @param info     the [StreamInfo] being downloaded
     * @param quality  the user-selected quality string, or null if not yet known
     * @return the sanitized filename (without extension)
     */
    @JvmStatic
    @JvmOverloads
    fun createFilenameWithTemplate(
        context: Context,
        info: StreamInfo,
        quality: String? = null
    ): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prefix = prefs.getStringSafe(
            context.getString(R.string.settings_filename_template_prefix_key),
            ""
        )
        val suffix = prefs.getStringSafe(
            context.getString(R.string.settings_filename_template_suffix_key),
            ""
        )

        val title = info.name.orEmpty()
        if (prefix.isEmpty() && suffix.isEmpty()) {
            return createFilename(context, title)
        }

        val uploader = info.uploaderName.orEmpty()
        val uploadDate = info.uploadDate?.offsetDateTime()
            ?.format(DateTimeFormatter.ISO_LOCAL_DATE).orEmpty()
        val qualityValue = quality.orEmpty()

        val expandedPrefix = expandPlaceholders(prefix, title, uploader, uploadDate, qualityValue)
        val expandedSuffix = expandPlaceholders(suffix, title, uploader, uploadDate, qualityValue)

        // Sanitize the assembled string as a whole so any illegal chars introduced by
        // literal text in the template (or by metadata values) are replaced consistently.
        return createFilename(context, expandedPrefix + title + expandedSuffix)
    }

    private fun expandPlaceholders(
        template: String,
        title: String,
        uploader: String,
        uploadDate: String,
        quality: String
    ): String {
        if (template.isEmpty()) return template
        return template
            .replace("{title}", title)
            .replace("{uploader}", uploader)
            .replace("{upload_date}", uploadDate)
            .replace("{quality}", quality)
    }

    /**
     * Create a valid filename.
     *
     * @param title the title to create a filename from
     * @param invalidCharacters patter matching invalid characters
     * @param replacementChar the replacement
     * @return the filename
     */
    private fun createFilename(
        title: String,
        invalidCharacters: String,
        replacementChar: String
    ): String {
        return title.replace(invalidCharacters.toRegex(), replacementChar)
    }
}
