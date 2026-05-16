package com.statefulscanner.model;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable scan request carrying a fully resolved target URL plus the
 * context needed to correlate the request with its source wordlist entry.
 *
 * <p>Instances are produced by
 * {@link com.statefulscanner.core.UrlGenerator#generateRequest(String)} or via
 * {@link #of(String, String, String)} when an orchestrator needs to construct
 * one directly. Equality is value-based on all components (record default), so
 * two requests with different ids are never equal even if they target the same
 * URL — this is intentional, since the id correlates a specific in-flight
 * attempt with its eventual response.
 *
 * <p><strong>Validation:</strong> {@code id}, {@code url}, {@code wordlistEntry},
 * and {@code baseUrl} are all required to be non-null. {@code url} must also be
 * non-blank because it is the only field consumers rely on for actually
 * dispatching work. {@code wordlistEntry} is intentionally <em>not</em> blank-checked
 * — callers may legitimately produce a request from a single-character or otherwise
 * unusual entry, and any blank-rejection should happen at the producing layer
 * (e.g. {@link com.statefulscanner.core.UrlGenerator#generateUrl(String)}).
 *
 * @param id            unique correlation id (used for logging / response join)
 * @param url           the absolute URL to fetch; non-blank
 * @param wordlistEntry the original (untrimmed) wordlist entry that produced the URL
 * @param baseUrl       the base URL the entry was combined with
 */
public record ScanRequest(UUID id, String url, String wordlistEntry, String baseUrl) {

    public ScanRequest {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(wordlistEntry, "wordlistEntry must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
    }

    /**
     * Convenience factory that assigns a fresh random {@link UUID}.
     *
     * @param url           the absolute URL to fetch (non-blank)
     * @param wordlistEntry the wordlist entry that produced the URL (non-null)
     * @param baseUrl       the base URL combined with the entry (non-null)
     * @return a new {@code ScanRequest} with a freshly generated id
     */
    public static ScanRequest of(String url, String wordlistEntry, String baseUrl) {
        return new ScanRequest(UUID.randomUUID(), url, wordlistEntry, baseUrl);
    }
}
