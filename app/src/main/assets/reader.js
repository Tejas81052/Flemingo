/*
 * Reader-mode content extractor.
 *
 * A dependency-free Readability-style pass tuned for Android WebView:
 * semantic-container preference, paragraph/ancestor scoring, link-density
 * penalties, useful sibling recovery, lazy-image promotion, and a strict
 * attribute allowlist. It always returns a small JSON-compatible object and
 * never throws across the WebView bridge.
 */
(function () {
    'use strict';

    var NEGATIVE_PATTERN = new RegExp(
        '-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|' +
        'disqus|extra|gdpr|legends|menu|related|remark|replies|rss|shoutbox|' +
        'sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|' +
        'pagination|pager|popup|comments-area|tags|widget|share|newsletter|' +
        'subscribe|byline-toolbar|promo|recommend|outbrain|taboola|cookie|' +
        'associated-pages|page-actions|content-actions|titlebar|jump-link',
        'i'
    );

    var POSITIVE_PATTERN = new RegExp(
        'article|article-body|body|content|entry|hentry|h-entry|main|page|' +
        'post|text|blog|story|essay|prose|read|chapter|parser-output',
        'i'
    );

    var REMOVE_TAGS = [
        'script', 'style', 'noscript', 'template', 'iframe', 'embed', 'object',
        'canvas', 'svg', 'nav', 'form', 'button', 'input', 'select', 'textarea'
    ];

    var KEEP_ATTRS = {
        a: ['href', 'title'],
        img: ['src', 'srcset', 'alt', 'title', 'width', 'height', 'loading'],
        source: ['src', 'srcset', 'type', 'media'],
        video: ['src', 'poster', 'controls', 'preload'],
        audio: ['src', 'controls', 'preload'],
        time: ['datetime'],
        td: ['colspan', 'rowspan'],
        th: ['colspan', 'rowspan', 'scope'],
        ol: ['start', 'reversed'],
        li: ['value']
    };

    var URL_ATTRS = ['href', 'src', 'poster'];
    var SCRIPT_URL_SCHEME = /^(?:javascript|vbscript):/i;

    function normalizedText(node) {
        return ((node && node.textContent) || '').replace(/\s+/g, ' ').trim();
    }

    function selectorText(el) {
        return ((el.className || '') + ' ' + (el.id || '')).toString();
    }

    function linkDensity(el) {
        var textLength = normalizedText(el).length;
        if (!textLength) return 1;
        var links = el.querySelectorAll('a');
        var linked = 0;
        for (var i = 0; i < links.length; i++) {
            linked += normalizedText(links[i]).length;
        }
        return linked / textLength;
    }

    function classWeight(el) {
        var value = selectorText(el);
        var weight = 0;
        if (NEGATIVE_PATTERN.test(value)) weight -= 35;
        if (POSITIVE_PATTERN.test(value)) weight += 30;
        if ((el.getAttribute('role') || '').toLowerCase() === 'main') weight += 25;
        if ((el.getAttribute('itemprop') || '').toLowerCase() === 'articlebody') weight += 50;
        return weight;
    }

    function baseScore(el) {
        var text = normalizedText(el);
        var textLength = text.length;
        if (textLength < 120) return -30;

        var tag = el.tagName.toLowerCase();
        var score = classWeight(el);
        if (el.id === 'mw-content-text' ||
            (el.classList && el.classList.contains('mw-parser-output'))) {
            score += 45;
        }
        if (tag === 'article') score += 45;
        else if (tag === 'main') score += 35;
        else if (tag === 'section') score += 10;

        score += Math.min(textLength / 75, 85);
        score += Math.min(el.querySelectorAll('p').length * 3.5, 42);
        score += Math.min((text.match(/[,.!?;:]/g) || []).length * 0.25, 24);

        var density = linkDensity(el);
        if (density > 0.65) score -= 55;
        else if (density > 0.45) score -= 30;
        else if (density < 0.15) score += 8;

        var controls = el.querySelectorAll('input, button, select, textarea').length;
        score -= Math.min(controls * 3, 24);
        return score;
    }

    function scoreCandidates() {
        var nodes = document.querySelectorAll(
            'article, main, [role="main"], [itemprop="articleBody"], ' +
            '#mw-content-text, .mw-parser-output, section, div'
        );
        var extras = new WeakMap();
        var paragraphs = document.querySelectorAll('p, pre, blockquote');

        // Paragraphs vote for their nearest content ancestors. This recovers
        // articles split into many sibling blocks inside a generic wrapper.
        for (var p = 0; p < paragraphs.length; p++) {
            var text = normalizedText(paragraphs[p]);
            if (text.length < 80) continue;
            var vote = 1 + Math.min(text.length / 120, 4) +
                Math.min((text.match(/,/g) || []).length, 3);
            var parent = paragraphs[p].parentElement;
            if (parent) extras.set(parent, (extras.get(parent) || 0) + vote);
            if (parent && parent.parentElement) {
                var grand = parent.parentElement;
                extras.set(grand, (extras.get(grand) || 0) + vote * 0.55);
            }
        }

        var best = null;
        var bestScore = -Infinity;
        for (var i = 0; i < nodes.length; i++) {
            var score = baseScore(nodes[i]) + (extras.get(nodes[i]) || 0);
            if (score > bestScore) {
                best = nodes[i];
                bestScore = score;
            }
        }
        return bestScore >= 34 ? { element: best, score: bestScore, extras: extras } : null;
    }

    function buildArticle(scored) {
        var best = scored.element;
        var parent = best.parentElement;
        if (!parent || best.tagName.toLowerCase() === 'main' ||
            best.tagName.toLowerCase() === 'article') {
            return best.cloneNode(true);
        }

        var wrapper = document.createElement('div');
        var siblings = parent.children;
        var threshold = Math.max(12, scored.score * 0.18);
        for (var i = 0; i < siblings.length; i++) {
            var sibling = siblings[i];
            var include = sibling === best;
            if (!include) {
                var siblingScore = baseScore(sibling) + (scored.extras.get(sibling) || 0);
                var siblingText = normalizedText(sibling);
                include = siblingScore >= threshold ||
                    (sibling.tagName.toLowerCase() === 'p' &&
                        siblingText.length >= 100 && linkDensity(sibling) < 0.25);
            }
            if (include) wrapper.appendChild(sibling.cloneNode(true));
        }
        return wrapper.childNodes.length ? wrapper : best.cloneNode(true);
    }

    function hasUnsafeScheme(value, isHref) {
        if (!value) return false;
        var normalized = value.replace(/[\u0000-\u0020]+/g, '');
        if (SCRIPT_URL_SCHEME.test(normalized)) return true;
        return isHref && /^data:/i.test(normalized);
    }

    function absolutize(value, isHref) {
        if (!value || hasUnsafeScheme(value, isHref)) return '';
        if (/^(?:data:image\/|blob:)/i.test(value) && !isHref) return value;
        try {
            return new URL(value, document.baseURI).href;
        } catch (e) {
            return '';
        }
    }

    function sanitizeSrcset(value) {
        if (!value) return '';
        var safe = [];
        var candidates = value.split(',');
        for (var i = 0; i < candidates.length; i++) {
            var candidate = candidates[i].trim();
            if (!candidate) continue;
            var parts = candidate.split(/\s+/);
            var url = absolutize(parts.shift(), false);
            if (url) safe.push(url + (parts.length ? ' ' + parts.join(' ') : ''));
        }
        return safe.join(', ');
    }

    function promoteLazyImages(root) {
        var images = root.querySelectorAll('img');
        var srcAttrs = ['data-src', 'data-original', 'data-lazy-src', 'data-url'];
        var srcsetAttrs = ['data-srcset', 'data-lazy-srcset'];
        for (var i = 0; i < images.length; i++) {
            var image = images[i];
            var src = image.getAttribute('src') || '';
            if (!src || /^data:image\/(?:gif|svg)/i.test(src)) {
                for (var s = 0; s < srcAttrs.length; s++) {
                    var candidate = image.getAttribute(srcAttrs[s]);
                    if (candidate) {
                        image.setAttribute('src', candidate);
                        break;
                    }
                }
            }
            if (!image.getAttribute('srcset')) {
                for (var ss = 0; ss < srcsetAttrs.length; ss++) {
                    var set = image.getAttribute(srcsetAttrs[ss]);
                    if (set) {
                        image.setAttribute('srcset', set);
                        break;
                    }
                }
            }
            image.setAttribute('loading', 'lazy');
        }
    }

    function clean(root, title) {
        promoteLazyImages(root);

        for (var t = 0; t < REMOVE_TAGS.length; t++) {
            var matches = root.querySelectorAll(REMOVE_TAGS[t]);
            for (var m = 0; m < matches.length; m++) matches[m].remove();
        }

        var all = root.querySelectorAll('*');
        for (var i = all.length - 1; i >= 0; i--) {
            var el = all[i];
            var combined = selectorText(el);
            var hidden = el.hasAttribute('hidden') ||
                (el.getAttribute('aria-hidden') || '').toLowerCase() === 'true';
            var role = (el.getAttribute('role') || '').toLowerCase();
            var structuralNoise = role === 'navigation' || role === 'menu' ||
                role === 'menubar' || role === 'tablist';
            if (hidden || structuralNoise ||
                (NEGATIVE_PATTERN.test(combined) && !POSITIVE_PATTERN.test(combined))) {
                el.remove();
            }
        }

        var elems = root.querySelectorAll('*');
        for (var j = 0; j < elems.length; j++) {
            var e = elems[j];
            var tagName = e.tagName.toLowerCase();
            var keepers = KEEP_ATTRS[tagName] || [];
            var attrs = [].slice.call(e.attributes);
            for (var k = 0; k < attrs.length; k++) {
                var name = attrs[k].name.toLowerCase();
                if (keepers.indexOf(name) === -1) {
                    e.removeAttribute(attrs[k].name);
                    continue;
                }
                if (name === 'srcset') {
                    var safeSet = sanitizeSrcset(attrs[k].value);
                    if (safeSet) e.setAttribute(name, safeSet);
                    else e.removeAttribute(name);
                } else if (URL_ATTRS.indexOf(name) !== -1) {
                    var safeUrl = absolutize(attrs[k].value, name === 'href');
                    if (safeUrl) e.setAttribute(name, safeUrl);
                    else e.removeAttribute(name);
                }
            }
            if (tagName === 'a') e.removeAttribute('target');
        }

        // The template already renders the canonical title. Drop a matching
        // leading H1 to avoid the common doubled-headline reader view.
        var firstHeading = root.querySelector('h1');
        if (firstHeading && title) {
            var headingText = normalizedText(firstHeading).toLowerCase();
            var titleText = title.toLowerCase();
            var titleStartsWithHeading = titleText === headingText ||
                titleText.indexOf(headingText + ' - ') === 0 ||
                titleText.indexOf(headingText + ' – ') === 0 ||
                titleText.indexOf(headingText + ' — ') === 0 ||
                titleText.indexOf(headingText + ' | ') === 0;
            if (titleStartsWithHeading) firstHeading.remove();
        }

        // Remove structurally empty wrappers while retaining media, rules and
        // table cells that are meaningful without text.
        var cleaned = root.querySelectorAll('p, div, section, span');
        for (var q = cleaned.length - 1; q >= 0; q--) {
            var node = cleaned[q];
            if (!normalizedText(node) &&
                !node.querySelector('img, picture, video, audio, table, pre, hr')) {
                node.remove();
            }
        }

        // Navigation chips and page-action lists can sit inside an otherwise
        // valid article container without useful class names. A short list
        // made almost entirely of links is navigation, not prose.
        var lists = root.querySelectorAll('ul, ol');
        for (var l = lists.length - 1; l >= 0; l--) {
            var listText = normalizedText(lists[l]);
            if (listText.length < 160 &&
                lists[l].querySelectorAll('a').length > 0 &&
                linkDensity(lists[l]) > 0.78) {
                lists[l].remove();
            }
        }
        return root.innerHTML;
    }

    function metaContent(selector) {
        var meta = document.querySelector(selector);
        return meta ? (meta.getAttribute('content') || '').trim() : '';
    }

    function getTitle() {
        var og = metaContent('meta[property="og:title"]');
        if (og) return og;
        var headline = document.querySelector('[itemprop="headline"], article h1, main h1, h1');
        if (headline && normalizedText(headline)) return normalizedText(headline);
        return (document.title || '').trim();
    }

    function getByline() {
        var meta = metaContent('meta[name="author"]') ||
            metaContent('meta[property="article:author"]');
        if (meta) return meta;
        var el = document.querySelector(
            '[class*="byline" i], [rel="author"], [itemprop="author"]'
        );
        var text = normalizedText(el);
        return text && text.length < 140 ? text : '';
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

    function getPublishedTime() {
        return metaContent('meta[property="article:published_time"]') ||
            metaContent('meta[name="date"]') ||
            metaContent('meta[itemprop="datePublished"]') ||
            ((document.querySelector('time[datetime]') || {}).dateTime || '');
    }

    try {
        var scored = scoreCandidates();
        if (!scored) return { eligible: false };

        var title = getTitle();
        var article = buildArticle(scored);
        var content = clean(article, title);
        var text = normalizedText(article);
        var wordCount = text ? text.split(/\s+/).length : 0;
        if (wordCount < 80 || content.length < 250) return { eligible: false };

        var docDir = (document.documentElement.dir || '').toLowerCase();
        return {
            eligible: true,
            title: title,
            byline: getByline(),
            siteName: getSiteName(),
            publishedTime: getPublishedTime(),
            content: content,
            wordCount: wordCount,
            lang: document.documentElement.lang || '',
            dir: docDir === 'rtl' ? 'rtl' : 'ltr'
        };
    } catch (e) {
        return { eligible: false, error: String(e) };
    }
})();
