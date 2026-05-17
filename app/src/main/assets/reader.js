/**
 * Reader-mode content extractor.
 *
 * Readability-lite: score every block-level candidate by text density,
 * class/id pattern hints, and link/text ratio; pick the highest-scoring
 * element; sanitise it; return the cleaned HTML.
 *
 * Designed to be invoked through android.webkit.WebView.evaluateJavascript;
 * its return value flows back to the Kotlin caller as a JSON-encoded
 * object that JSONObject parses directly. On failure or non-eligible
 * pages we return { eligible: false }, never throw — the caller treats
 * "not eligible" identically to "no content found".
 *
 * Accuracy: about 70-80% on news/blog sites with a recognisable
 * article structure. App-shell SPAs (Reddit, Twitter, etc.) lack
 * server-rendered article content, so the extractor correctly bails
 * out instead of producing a broken reader view.
 */
(function () {
    'use strict';

    /**
     * Class/id substrings that are reliable indicators of *non-content*
     * regions. An element matching this and not also matching
     * POSITIVE_PATTERN gets a meaningful negative score and is also
     * removed wholesale by the cleaner pass.
     */
    var NEGATIVE_PATTERN = new RegExp(
        '-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|' +
        'disqus|extra|gdpr|legends|menu|related|remark|replies|rss|shoutbox|' +
        'sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|' +
        'pagination|pager|popup|footnote|comments-area|tags|widget|share|' +
        'newsletter|subscribe|metadata|byline-toolbar|promo|cta',
        'i'
    );

    /**
     * Class/id substrings that hint at *content* regions. Matching gives
     * a moderate positive score; the cleaner pass keeps elements with
     * these markers even when they also match NEGATIVE_PATTERN — e.g.
     * "post-comments" matches both but we trust the explicit positive.
     */
    var POSITIVE_PATTERN = new RegExp(
        'article|body|content|entry|hentry|h-entry|main|page|post|text|' +
        'blog|story|essay|prose',
        'i'
    );

    /** Tags we always strip from the article body. Scripts/styles for
     *  obvious reasons; iframes/embed/object because they're typically
     *  third-party ad/widget surfaces. */
    var REMOVE_TAGS = [
        'script', 'style', 'noscript', 'iframe', 'embed', 'object',
        'aside', 'nav', 'form', 'button', 'input', 'select', 'textarea',
        'footer', 'header'
    ];

    /** Per-tag attribute allowlist. Anything not listed is stripped. */
    var KEEP_ATTRS = {
        a: ['href'],
        img: ['src', 'alt'],
        source: ['src', 'srcset', 'type'],
        video: ['src', 'poster', 'controls'],
        audio: ['src', 'controls'],
        time: ['datetime']
    };

    function combinedSelector(el) {
        return ((el.className || '') + ' ' + (el.id || '')).toString();
    }

    function scoreElement(el) {
        var combined = combinedSelector(el);
        var score = 0;

        if (NEGATIVE_PATTERN.test(combined)) score -= 30;
        if (POSITIVE_PATTERN.test(combined)) score += 25;

        var text = (el.textContent || '');
        var textLen = text.replace(/\s+/g, ' ').trim().length;
        if (textLen < 100) {
            // Too short to be the article body, but might still hold one.
            score -= 10;
        } else {
            // Diminishing returns past a few thousand chars so massive
            // body wrappers don't always win over more specific children.
            score += Math.min(textLen / 80, 50);
        }

        var paragraphs = el.querySelectorAll('p');
        score += Math.min(paragraphs.length * 3, 30);

        // Link-density penalty. Nav/sidebar/related-articles blocks have
        // a high ratio of link text to total text; real article bodies
        // are mostly prose with occasional inline links.
        var links = el.querySelectorAll('a');
        var linkText = 0;
        for (var i = 0; i < links.length; i++) {
            linkText += (links[i].textContent || '').length;
        }
        if (textLen > 0) {
            var ratio = linkText / textLen;
            if (ratio > 0.5) score -= 20;
        }

        // Tag affinity — <article>/<main> are pretty good signals.
        var tag = el.tagName.toLowerCase();
        if (tag === 'article' || tag === 'main') score += 30;

        return score;
    }

    function pickContent() {
        var candidates = document.querySelectorAll('article, main, section, div');
        var best = null;
        var bestScore = -Infinity;
        for (var i = 0; i < candidates.length; i++) {
            var s = scoreElement(candidates[i]);
            if (s > bestScore) {
                bestScore = s;
                best = candidates[i];
            }
        }
        // Reject if even the best is junk. Threshold tuned to reject
        // app-shell SPAs (Twitter/Reddit-style pages with minimal SSR
        // content) while accepting most blog posts and news articles.
        if (bestScore < 30) return null;
        return best;
    }

    function clean(element) {
        var clone = element.cloneNode(true);

        // Whole-element removal: scripts, styles, embeds, etc.
        for (var t = 0; t < REMOVE_TAGS.length; t++) {
            var matches = clone.querySelectorAll(REMOVE_TAGS[t]);
            for (var m = 0; m < matches.length; m++) {
                matches[m].remove();
            }
        }

        // Class/id-based negative removal — sidebars, ads, etc. that
        // happen to be wrapped in a div the extractor kept.
        var all = clone.querySelectorAll('*');
        for (var i = all.length - 1; i >= 0; i--) {
            var el = all[i];
            var combined = combinedSelector(el);
            if (NEGATIVE_PATTERN.test(combined) && !POSITIVE_PATTERN.test(combined)) {
                el.remove();
            }
        }

        // Attribute strip: keep only allowlisted attrs per tag. Inline
        // styles and event handlers are universal noise / risk and go.
        var elems = clone.querySelectorAll('*');
        for (var j = 0; j < elems.length; j++) {
            var e = elems[j];
            var tagName = e.tagName.toLowerCase();
            var keepers = KEEP_ATTRS[tagName] || [];
            var attrs = [].slice.call(e.attributes);
            for (var k = 0; k < attrs.length; k++) {
                var name = attrs[k].name.toLowerCase();
                if (keepers.indexOf(name) === -1) {
                    e.removeAttribute(attrs[k].name);
                }
            }
        }

        return clone.innerHTML;
    }

    function metaContent(selector) {
        var meta = document.querySelector(selector);
        return meta ? (meta.getAttribute('content') || '').trim() : '';
    }

    function getTitle() {
        // Open Graph first (most accurate when present), then <h1>,
        // then the document title (which often has " | Site Name"
        // suffixes — better than nothing).
        var og = metaContent('meta[property="og:title"]');
        if (og) return og;
        var h1 = document.querySelector('h1');
        if (h1 && h1.textContent.trim()) return h1.textContent.trim();
        return (document.title || '').trim();
    }

    function getByline() {
        var meta = metaContent('meta[name="author"]') ||
            metaContent('meta[property="article:author"]');
        if (meta) return meta;
        // Visible byline element — only accept short text (< 100 chars)
        // so we don't accidentally pick up the entire author bio block.
        var el = document.querySelector(
            '[class*="byline" i], [rel="author"], [itemprop="author"]'
        );
        if (el) {
            var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
            if (text && text.length < 100) return text;
        }
        return '';
    }

    function getSiteName() {
        var og = metaContent('meta[property="og:site_name"]');
        if (og) return og;
        try {
            return (location.hostname || '').replace(/^www\./, '');
        } catch (e) {
            return '';
        }
    }

    try {
        var element = pickContent();
        if (!element) {
            return { eligible: false };
        }

        var content = clean(element);
        var text = (element.textContent || '').replace(/\s+/g, ' ').trim();
        var wordCount = text ? text.split(/\s+/).length : 0;

        return {
            eligible: true,
            title: getTitle(),
            byline: getByline(),
            siteName: getSiteName(),
            content: content,
            wordCount: wordCount,
            lang: document.documentElement.lang || ''
        };
    } catch (e) {
        // Never throw across the JS<->Kotlin bridge; the caller treats
        // "not eligible" identically to errors, and a thrown exception
        // would just become a null result with no diagnostic.
        return { eligible: false, error: String(e) };
    }
})();
