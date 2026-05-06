package com.statefulscanner.core;

import com.statefulscanner.model.ScanRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates absolute target URLs by combining a fixed base URL with wordlist entries,
 * optionally substituting them into a path template.
 *
 * <h2>Pattern syntax</h2>
 * Patterns are plain strings containing the literal placeholder {@value #PLACEHOLDER}
 * which is replaced by the wordlist entry. Examples (base = {@code https://example.com}):
 * <ul>
 *   <li>pattern {@code null} or empty &rarr; {@code https://example.com/<entry>}</li>
 *   <li>pattern {@code /admin/{WORD}} &rarr; {@code https://example.com/admin/<entry>}</li>
 *   <li>pattern {@code /api/{WORD}/users} &rarr; {@code https://example.com/api/<entry>/users}</li>
 * </ul>
 * A non-null, non-empty pattern must contain {@value #PLACEHOLDER}; otherwise the
 * constructor throws {@link IllegalArgumentException} (a pattern with no substitution
 * point would generate the same URL for every wordlist entry, which is never the
 * caller's intent). Multiple occurrences of the placeholder are all replaced.
 *
 * <h2>Base URL handling</h2>
 * The base URL is parsed with the lenient single-argument {@link URI} constructor,
 * so it must already be syntactically valid per RFC 3986 — pre-encode any reserved
 * characters in user-supplied bases before passing them in. The scheme must be
 * {@code http} or {@code https} (case-insensitive), and a host is required.
 *
 * <p><strong>Query &amp; fragment carry-over:</strong> if the base URL contains a
 * query string or fragment, every generated URL carries them forward. This is
 * useful for endpoints that require a fixed query parameter (e.g. an API token)
 * but is a footgun if you intended the query/fragment to apply only to a probe
 * page. Strip them from the base URL beforehand if you need fresh probes.
 *
 * <h2>Normalization &amp; encoding</h2>
 * Generated URLs are produced via the multi-argument {@link URI} constructor,
 * which percent-encodes path characters that are illegal in URI syntax (RFC 3986).
 * Callers should provide raw, unencoded entries — pre-encoded sequences will be
 * double-encoded. Consecutive {@code /} characters in the path are collapsed to a
 * single slash <em>before</em> encoding, so multi-segment entries like
 * {@code a//b///c} become {@code a/b/c}.
 *
 * <p><strong>Control characters are rejected.</strong> Wordlist entries containing
 * any character below U+0020 or U+007F (DEL) cause {@link #generateUrl(String)} to
 * throw {@link IllegalArgumentException}. Raw {@code URI} would silently percent-encode
 * these (e.g. CRLF &rarr; {@code %0D%0A}); for a security tool that is the wrong
 * default — a malicious wordlist could otherwise smuggle CR/LF or NUL bytes into
 * downstream HTTP processing. Reject explicitly, defence-in-depth.
 *
 * <h2>Length cap</h2>
 * The generated URL is rejected if it exceeds {@link #MAX_URL_LENGTH} characters.
 * The cap is also pre-checked against the raw entry to avoid allocating a multi-megabyte
 * string from a pathological wordlist line.
 *
 * <h2>Thread-safety</h2>
 * Instances are immutable and safe for concurrent use by virtual threads.
 */
public final class UrlGenerator {

    /** Placeholder token recognised inside path patterns. */
    public static final String PLACEHOLDER = "{WORD}";

    /**
     * Hard upper bound on the length of a generated URL. The HTTP spec sets no
     * limit but most servers/proxies (and the default Tomcat config) reject
     * request lines beyond ~8 KB.
     */
    public static final int MAX_URL_LENGTH = 8192;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final URI baseUri;
    private final String pattern;

    /**
     * Creates a generator that simply appends each wordlist entry to the base URL's path.
     *
     * @param baseUrl absolute http(s) URL with a host (e.g. {@code https://example.com})
     * @throws IllegalArgumentException if {@code baseUrl} is malformed, missing a host,
     *                                  or uses a scheme other than {@code http}/{@code https}
     */
    public UrlGenerator(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * Creates a generator that substitutes wordlist entries into the given pattern
     * before appending to the base URL's path.
     *
     * @param baseUrl absolute http(s) URL with a host
     * @param pattern path template containing {@value #PLACEHOLDER}, or {@code null}/empty
     *                to append entries directly
     * @throws IllegalArgumentException if {@code baseUrl} is invalid or
     *                                  {@code pattern} is non-empty but contains no placeholder
     */
    public UrlGenerator(String baseUrl, String pattern) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.baseUri = parseAndValidateBase(baseUrl);
        this.pattern = (pattern == null || pattern.isEmpty()) ? null : validatePattern(pattern);
    }

    /**
     * Builds an absolute URL for the given wordlist entry.
     *
     * @param wordlistEntry the entry to combine with the base URL; whitespace is trimmed
     * @return the absolute, normalized, percent-encoded URL
     * @throws IllegalArgumentException if {@code wordlistEntry} is blank, contains a
     *                                  control character (&lt; U+0020 or U+007F),
     *                                  the resulting URL is syntactically invalid, or
     *                                  it exceeds {@link #MAX_URL_LENGTH}
     */
    public String generateUrl(String wordlistEntry) {
        Objects.requireNonNull(wordlistEntry, "wordlistEntry must not be null");
        String entry = wordlistEntry.trim();
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("wordlistEntry must not be blank");
        }
        rejectControlCharacters(entry);
        // Cheap pre-check before we build any larger strings — a wordlist line itself
        // longer than the URL cap can't possibly produce a compliant URL.
        if (entry.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "wordlistEntry exceeds maximum length of " + MAX_URL_LENGTH
                            + " characters: " + entry.length());
        }

        String segment = (pattern == null) ? entry : pattern.replace(PLACEHOLDER, entry);
        String basePath = baseUri.getPath() == null ? "" : baseUri.getPath();
        String combinedPath = combinePath(basePath, segment);
        String normalizedPath = collapseSlashes(combinedPath);

        URI generated;
        try {
            generated = new URI(
                    baseUri.getScheme(),
                    baseUri.getUserInfo(),
                    baseUri.getHost(),
                    baseUri.getPort(),
                    normalizedPath,
                    baseUri.getQuery(),
                    baseUri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "Generated URL is syntactically invalid for entry '" + wordlistEntry + "': "
                            + e.getMessage(), e);
        }

        String url = generated.toASCIIString();
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "Generated URL exceeds maximum length of " + MAX_URL_LENGTH
                            + " characters: " + url.length());
        }
        return url;
    }

    /**
     * Builds a {@link ScanRequest} for the given wordlist entry, combining the
     * generated URL with correlation metadata.
     *
     * @param wordlistEntry the entry to combine with the base URL
     * @return a populated {@code ScanRequest} carrying a fresh correlation id
     * @throws IllegalArgumentException see {@link #generateUrl(String)}
     */
    public ScanRequest generateRequest(String wordlistEntry) {
        String url = generateUrl(wordlistEntry);
        return ScanRequest.of(url, wordlistEntry, baseUri.toASCIIString());
    }

    /**
     * Maps a stream of wordlist entries to {@link ScanRequest} instances. The
     * returned stream is lazy — each request is built on demand, which composes
     * cleanly with {@code WordlistService.readWordlist(String)}.
     *
     * <p>If you want to keep going past the first malformed entry (e.g. log and
     * skip), wrap {@link #generateRequest(String)} yourself with a try/catch
     * inside {@code map}; this method intentionally lets exceptions propagate so
     * the default behaviour is fail-fast.
     *
     * @param entries non-null stream of wordlist entries
     * @return a lazy stream of scan requests
     */
    public Stream<ScanRequest> generateRequests(Stream<String> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        return entries.map(this::generateRequest);
    }

    /** @return the base URL the generator was configured with, percent-encoded. */
    public String baseUrl() {
        return baseUri.toASCIIString();
    }

    /**
     * @return the configured path template, or empty if the generator simply
     *         appends entries to the base path
     */
    public Optional<String> pattern() {
        return Optional.ofNullable(pattern);
    }

    private static URI parseAndValidateBase(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Base URL must be absolute: " + baseUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Base URL must use http or https scheme: " + baseUrl);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Base URL must have a host: " + baseUrl);
        }
        return uri;
    }

    private static String validatePattern(String pattern) {
        if (!pattern.contains(PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "Pattern must contain placeholder " + PLACEHOLDER + ": " + pattern);
        }
        rejectControlCharacters(pattern);
        return pattern;
    }

    private static void rejectControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(
                        "Value contains control character at index " + i
                                + " (U+" + String.format(Locale.ROOT, "%04X", (int) c) + ")");
            }
        }
    }

    private static String combinePath(String basePath, String segment) {
        boolean baseEndsWithSlash = basePath.endsWith("/");
        boolean segmentStartsWithSlash = segment.startsWith("/");
        if (baseEndsWithSlash && segmentStartsWithSlash) {
            return basePath + segment.substring(1);
        }
        if (!baseEndsWithSlash && !segmentStartsWithSlash) {
            return basePath + "/" + segment;
        }
        return basePath + segment;
    }

    private static String collapseSlashes(String path) {
        return path.replaceAll("/{2,}", "/");
    }
}
