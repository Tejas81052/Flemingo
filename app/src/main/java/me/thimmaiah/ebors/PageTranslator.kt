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

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class TranslationLanguage(
    val name: String,
    val code: String,
) {
    val label: String get() = "$name · $code"
}

/**
 * Language catalogue and URL handling for page translation.
 *
 * The catalogue mirrors Google Cloud Translation's NMT language list (June
 * 2026). The browser still uses Google Translate's public page proxy rather
 * than the paid Cloud API; keeping the codes in one pure-Kotlin object makes
 * validation, searching, and URL construction testable.
 */
internal object PageTranslator {
    val languages: List<TranslationLanguage> by lazy {
        LANGUAGE_DATA.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { row ->
                val separator = row.lastIndexOf('|')
                TranslationLanguage(
                    name = row.substring(0, separator),
                    code = row.substring(separator + 1),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private val byCode: Map<String, TranslationLanguage> by lazy {
        languages.associateBy { it.code.lowercase() }
    }

    fun find(code: String?): TranslationLanguage? {
        val normalized = code?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return null
        return byCode[normalized]
    }

    fun preferred(deviceLanguageTag: String?, persistedCode: String?): TranslationLanguage {
        find(persistedCode)?.let { return it }
        val tag = deviceLanguageTag.orEmpty()
        find(tag)?.let { return it }
        find(tag.substringBefore('-'))?.let { return it }
        return find("en") ?: languages.first()
    }

    fun buildProxyUrl(sourceUrl: String, targetCode: String): String? {
        if (!isHttpUrl(sourceUrl)) return null
        val language = find(targetCode) ?: return null
        val encoded = URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8.name())
        return "https://translate.google.com/translate?sl=auto&tl=${language.code}&u=$encoded"
    }

    fun isTranslationUrl(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return host == "translate.google.com" || host.endsWith(".translate.goog")
    }

    /**
     * Recover the source from the initial translate.google.com URL. Redirected
     * *.translate.goog hosts are intentionally not reverse-engineered; the Tab
     * keeps the original URL for those.
     */
    fun extractSourceUrl(url: String?): String? {
        val source = url ?: return null
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        if (!uri.host.equals("translate.google.com", ignoreCase = true)) return null
        val rawValue = uri.rawQuery.orEmpty()
            .split('&')
            .firstOrNull { it.substringBefore('=') == "u" }
            ?.substringAfter('=', "")
            ?: return null
        val decoded = runCatching {
            URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
        }.getOrNull()
        return decoded?.takeIf(::isHttpUrl)
    }

    private fun hostOf(url: String?): String? =
        url?.let { source -> runCatching { URI(source).host?.lowercase() }.getOrNull() }

    private fun isHttpUrl(url: String): Boolean =
        runCatching {
            // WebView normally hands us an already-escaped URL, but accepting
            // an occasional literal space makes the helper resilient to
            // restored/externally supplied addresses before we encode them.
            val scheme = URI(url.replace(" ", "%20")).scheme
            scheme.equals("http", true) || scheme.equals("https", true)
        }.getOrDefault(false)

    // name|target-code. Names are deliberately explicit instead of relying on
    // the device ICU database, because newer language codes can otherwise show
    // up as opaque three-letter identifiers on older supported Android builds.
    private const val LANGUAGE_DATA = """
        Abkhaz|ab
        Acehnese|ace
        Acholi|ach
        Afrikaans|af
        Albanian|sq
        Alur|alz
        Amharic|am
        Arabic|ar
        Armenian|hy
        Assamese|as
        Awadhi|awa
        Aymara|ay
        Azerbaijani|az
        Balinese|ban
        Bambara|bm
        Bashkir|ba
        Basque|eu
        Batak Karo|btx
        Batak Simalungun|bts
        Batak Toba|bbc
        Belarusian|be
        Bemba|bem
        Bengali|bn
        Betawi|bew
        Bhojpuri|bho
        Bikol|bik
        Bosnian|bs
        Breton|br
        Bulgarian|bg
        Buryat|bua
        Cantonese|yue
        Catalan|ca
        Cebuano|ceb
        Chichewa (Nyanja)|ny
        Chinese (Simplified)|zh-CN
        Chinese (Traditional)|zh-TW
        Chuvash|cv
        Corsican|co
        Crimean Tatar|crh
        Croatian|hr
        Czech|cs
        Danish|da
        Dinka|din
        Divehi|dv
        Dogri|doi
        Dombe|dov
        Dutch|nl
        Dzongkha|dz
        English|en
        Esperanto|eo
        Estonian|et
        Ewe|ee
        Fijian|fj
        Filipino (Tagalog)|fil
        Finnish|fi
        French|fr
        French (Canadian)|fr-CA
        Frisian|fy
        Fulfulde|ff
        Ga|gaa
        Galician|gl
        Ganda (Luganda)|lg
        Georgian|ka
        German|de
        Greek|el
        Guarani|gn
        Gujarati|gu
        Haitian Creole|ht
        Hakha Chin|cnh
        Hausa|ha
        Hawaiian|haw
        Hebrew|he
        Hiligaynon|hil
        Hindi|hi
        Hmong|hmn
        Hungarian|hu
        Hunsrik|hrx
        Icelandic|is
        Igbo|ig
        Iloko|ilo
        Indonesian|id
        Irish|ga
        Italian|it
        Japanese|ja
        Javanese|jv
        Kannada|kn
        Kapampangan|pam
        Kazakh|kk
        Khmer|km
        Kiga|cgg
        Kinyarwanda|rw
        Kituba|ktu
        Konkani|gom
        Korean|ko
        Krio|kri
        Kurdish (Kurmanji)|ku
        Kurdish (Sorani)|ckb
        Kyrgyz|ky
        Lao|lo
        Latgalian|ltg
        Latin|la
        Latvian|lv
        Ligurian|lij
        Limburgan|li
        Lingala|ln
        Lithuanian|lt
        Lombard|lmo
        Luo|luo
        Luxembourgish|lb
        Macedonian|mk
        Maithili|mai
        Makassar|mak
        Malagasy|mg
        Malay|ms
        Malay (Jawi)|ms-Arab
        Malayalam|ml
        Maltese|mt
        Maori|mi
        Marathi|mr
        Meadow Mari|chm
        Meiteilon (Manipuri)|mni-Mtei
        Minang|min
        Mizo|lus
        Mongolian|mn
        Myanmar (Burmese)|my
        Ndebele (South)|nr
        Nepalbhasa (Newari)|new
        Nepali|ne
        Northern Sotho (Sepedi)|nso
        Norwegian|no
        Nuer|nus
        Occitan|oc
        Odia (Oriya)|or
        Oromo|om
        Pangasinan|pag
        Papiamento|pap
        Pashto|ps
        Persian|fa
        Polish|pl
        Portuguese|pt
        Portuguese (Brazil)|pt-BR
        Portuguese (Portugal)|pt-PT
        Punjabi|pa
        Punjabi (Shahmukhi)|pa-Arab
        Quechua|qu
        Romani|rom
        Romanian|ro
        Rundi|rn
        Russian|ru
        Samoan|sm
        Sango|sg
        Sanskrit|sa
        Scots Gaelic|gd
        Serbian|sr
        Sesotho|st
        Seychellois Creole|crs
        Shan|shn
        Shona|sn
        Sicilian|scn
        Silesian|szl
        Sindhi|sd
        Sinhala (Sinhalese)|si
        Slovak|sk
        Slovenian|sl
        Somali|so
        Spanish|es
        Sundanese|su
        Swahili|sw
        Swati|ss
        Swedish|sv
        Tajik|tg
        Tamil|ta
        Tatar|tt
        Telugu|te
        Tetum|tet
        Thai|th
        Tigrinya|ti
        Tsonga|ts
        Tswana|tn
        Turkish|tr
        Turkmen|tk
        Twi (Akan)|ak
        Ukrainian|uk
        Urdu|ur
        Uyghur|ug
        Uzbek|uz
        Vietnamese|vi
        Welsh|cy
        Xhosa|xh
        Yiddish|yi
        Yoruba|yo
        Yucatec Maya|yua
        Zulu|zu
    """
}
