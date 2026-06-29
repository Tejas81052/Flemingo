/*
 * Copyright 2026 Tejas Thimmaiah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.thimmaiah.ebors

/**
 * Pure (no Android dependencies) filename sanitiser for downloads.
 *
 * Extracted from [DownloadRepository] for two reasons:
 *
 *  1. [DownloadRepository] is an `object` (singleton) that builds an
 *     `android.os.Handler` at class-init time. That handler NPEs in
 *     plain JVM unit tests where `Looper.getMainLooper()` is null.
 *     Moving the sanitiser into its own object lets us drive a table
 *     of malicious inputs without dragging the singleton in.
 *  2. The function has no other dependencies, so isolating it makes
 *     the security-load-bearing logic auditable in one place.
 *
 * Threat model: a server-controlled `Content-Disposition: attachment;
 * filename="../../etc/passwd"` shouldn't be able to write outside the
 * downloads directory. Path separators and parent-dir markers are
 * scrubbed; ASCII control characters are stripped; leading / trailing
 * dots and spaces (which trip up Windows / zip clients) are trimmed.
 */
internal object DownloadFileNames {

    fun sanitize(fileName: String): String {
        // 1a. Trim leading / trailing dots and whitespace first. This
        //     order matters: replacing spaces with underscores before
        //     trimming would turn `"   "` into `"___"` and we'd be left
        //     with a meaningless three-underscore filename rather than
        //     hitting the `ifBlank` fallback below.
        // 1b. Then replace structural characters that could otherwise
        //     reposition where the file is written. Spaces are replaced
        //     too — not for a security reason but because spaces in
        //     download filenames play poorly with shell tooling and
        //     some MIME-aware viewers.
        val withoutSeparators = fileName
            .trim('.', ' ')
            .replace('\\', '_')
            .replace('/', '_')
            .replace(' ', '_')

        // 2. Collapse any run of dots down to a single dot. This
        //    neutralises path-traversal markers like `..`, `....`,
        //    `.../`, etc., regardless of how they were obfuscated.
        // 3. Strip ASCII control characters. Filesystems on Android
        //    typically tolerate them but many user-facing apps don't
        //    render them, and they can be used to hide the real
        //    extension (e.g. evil.exe<U+202E>fdp.txt-style attacks
        //    are a separate concern, but stripping <0x1f also kills
        //    a related class of obfuscation).
        val cleaned = withoutSeparators
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("[\\x00-\\x1f]"), "")

        return cleaned.ifBlank { "download" }
    }
}
