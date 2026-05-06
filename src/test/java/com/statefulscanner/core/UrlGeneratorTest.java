package com.statefulscanner.core;

import com.statefulscanner.model.ScanRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class UrlGeneratorTest {

    @Nested
    class Construction {

        @Test
        void constructor_withNullBaseUrl_throwsNullPointerException() {
            assertThatThrownBy(() -> new UrlGenerator(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"not a url", "://missing-scheme", "http://", "https://"})
        void constructor_withMalformedBaseUrl_throwsIllegalArgumentException(String badUrl) {
            assertThatThrownBy(() -> new UrlGenerator(badUrl))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void constructor_withRelativeUrl_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new UrlGenerator("/path/only"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("absolute");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://example.com", "file:///etc/passwd", "javascript:alert(1)"})
        void constructor_withDisallowedScheme_throwsIllegalArgumentException(String badUrl) {
            assertThatThrownBy(() -> new UrlGenerator(badUrl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("http or https");
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://example.com", "HTTP://example.com", "https://example.com",
                "HTTPS://example.com"})
        void constructor_acceptsHttpAndHttpsCaseInsensitive(String baseUrl) {
            // Behaviour: scheme casing is accepted but NOT normalized — baseUrl()
            // round-trips the input scheme verbatim. Asserting that exact
            // round-trip prevents the test from silently passing on weaker
            // implementations that just check non-blankness.
            UrlGenerator generator = new UrlGenerator(baseUrl);

            assertThat(generator.baseUrl()).isEqualTo(baseUrl);
        }

        @Test
        void constructor_withPatternMissingPlaceholder_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new UrlGenerator("https://example.com", "/admin/static"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{WORD}");
        }

        @Test
        void constructor_withNullPattern_treatsAsSimpleAppend() {
            UrlGenerator generator = new UrlGenerator("https://example.com", null);

            assertThat(generator.pattern()).isEmpty();
            assertThat(generator.generateUrl("admin"))
                    .isEqualTo("https://example.com/admin");
        }

        @Test
        void constructor_withEmptyPattern_treatsAsSimpleAppend() {
            UrlGenerator generator = new UrlGenerator("https://example.com", "");

            assertThat(generator.pattern()).isEmpty();
            assertThat(generator.generateUrl("admin"))
                    .isEqualTo("https://example.com/admin");
        }

        @Test
        void constructor_withValidPattern_storesPatternForRetrieval() {
            UrlGenerator generator = new UrlGenerator("https://example.com", "/api/{WORD}");

            assertThat(generator.pattern()).contains("/api/{WORD}");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/{WORD}\n", "/api/{WORD}\t", "/api/{WORD}\u0000",
                "/api/{WORD}\u007F"})
        void constructor_withControlCharsInPattern_throwsIllegalArgumentException(String badPattern) {
            // Architect-added behaviour: control chars are rejected on the pattern
            // itself (not just on entries), preventing pattern-side smuggling of
            // CR/LF/NUL into generated URLs.
            assertThatThrownBy(() -> new UrlGenerator("https://example.com", badPattern))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");
        }
    }

    @Nested
    class SimpleGeneration {

        @Test
        void generateUrl_withNullEntry_throwsNullPointerException() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThatThrownBy(() -> generator.generateUrl(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void generateUrl_withBlankEntry_throwsIllegalArgumentException(String blank) {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThatThrownBy(() -> generator.generateUrl(blank))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @ParameterizedTest
        @CsvSource({
                "https://example.com,         admin,        https://example.com/admin",
                "https://example.com/,        admin,        https://example.com/admin",
                "https://example.com,         /admin,       https://example.com/admin",
                "https://example.com/,        /admin,       https://example.com/admin",
                "https://example.com/api,     users,        https://example.com/api/users",
                "https://example.com/api/,    users,        https://example.com/api/users",
                "https://example.com/api,     /users,       https://example.com/api/users",
                "https://example.com/api/,    /users,       https://example.com/api/users"
        })
        void generateUrl_handlesSlashCombinations(String base, String entry, String expected) {
            UrlGenerator generator = new UrlGenerator(base);

            assertThat(generator.generateUrl(entry)).isEqualTo(expected);
        }

        @Test
        void generateUrl_trimsLeadingAndTrailingWhitespace() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("  admin  "))
                    .isEqualTo("https://example.com/admin");
        }

        @Test
        void generateUrl_preservesPort() {
            UrlGenerator generator = new UrlGenerator("https://example.com:8443");

            assertThat(generator.generateUrl("admin"))
                    .isEqualTo("https://example.com:8443/admin");
        }

        @Test
        void generateUrl_preservesQueryAndFragment() {
            UrlGenerator generator = new UrlGenerator("https://example.com/api?token=abc#frag");

            String url = generator.generateUrl("users");

            assertThat(url).contains("/api/users");
            assertThat(url).contains("token=abc");
            assertThat(url).endsWith("#frag");
        }

        @Test
        void generateUrl_supportsMultiSegmentEntries() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("a/b/c"))
                    .isEqualTo("https://example.com/a/b/c");
        }

        @Test
        void generateUrl_preservesIpv6Host() {
            UrlGenerator generator = new UrlGenerator("https://[::1]:8080");

            assertThat(generator.generateUrl("admin"))
                    .isEqualTo("https://[::1]:8080/admin");
        }

        @Test
        void generateUrl_preservesUserinfo() {
            UrlGenerator generator = new UrlGenerator("https://user:pw@example.com");

            assertThat(generator.generateUrl("admin"))
                    .isEqualTo("https://user:pw@example.com/admin");
        }
    }

    @Nested
    class Normalization {

        @Test
        void generateUrl_collapsesDuplicateSlashesInEntry() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("a//b///c"))
                    .isEqualTo("https://example.com/a/b/c");
        }

        @Test
        void generateUrl_doesNotCollapseSchemeSlashes() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            String url = generator.generateUrl("admin");

            assertThat(url).startsWith("https://");
        }

        @Test
        void generateUrl_collapsesBoundarySlashes() {
            UrlGenerator generator = new UrlGenerator("https://example.com/api/");

            assertThat(generator.generateUrl("///users"))
                    .isEqualTo("https://example.com/api/users");
        }
    }

    @Nested
    class Encoding {

        @Test
        void generateUrl_percentEncodesSpaces() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("hello world"))
                    .isEqualTo("https://example.com/hello%20world");
        }

        @Test
        void generateUrl_percentEncodesUnicode() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            String url = generator.generateUrl("caf\u00e9");

            assertThat(url).isEqualTo("https://example.com/caf%C3%A9");
        }

        @Test
        void generateUrl_preservesUnreservedCharacters() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("ABC-abc_123.~"))
                    .isEqualTo("https://example.com/ABC-abc_123.~");
        }
    }

    @Nested
    class ControlCharacterRejection {

        // Architect-added behaviour: every character below U+0020 and U+007F (DEL)
        // must be rejected explicitly rather than silently percent-encoded, so a
        // malicious wordlist cannot smuggle CRLF or NUL into downstream HTTP.

        @ParameterizedTest
        @ValueSource(strings = {
                "ad\u0000min",    // NUL
                "ad\u0001min",    // SOH
                "ad\rmin",        // CR
                "ad\nmin",        // LF
                "ad\r\nmin",      // CRLF (the canonical request-smuggling vector)
                "ad\u001Fmin",    // boundary: just below U+0020
                "ad\u007Fmin"     // DEL
        })
        void generateUrl_withControlCharInEntry_throwsIllegalArgumentException(String entry) {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThatThrownBy(() -> generator.generateUrl(entry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");
        }

        @Test
        void generateUrl_atSpaceBoundary_isNotRejectedAsControlChar() {
            // U+0020 (space) is the lowest non-control character — it must encode,
            // not throw. Guards against off-by-one in the < 0x20 check.
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("hello world"))
                    .isEqualTo("https://example.com/hello%20world");
        }

        @Test
        void generateUrl_at0x7EBoundary_isNotRejectedAsControlChar() {
            // U+007E (~) is just below DEL (U+007F) — it must be preserved.
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThat(generator.generateUrl("a~b"))
                    .isEqualTo("https://example.com/a~b");
        }
    }

    @Nested
    class PatternSubstitution {

        @Test
        void generateUrl_substitutesPlaceholderInPattern() {
            UrlGenerator generator = new UrlGenerator("https://example.com", "/admin/{WORD}");

            assertThat(generator.generateUrl("login"))
                    .isEqualTo("https://example.com/admin/login");
        }

        @Test
        void generateUrl_substitutesMultipleOccurrencesOfPlaceholder() {
            UrlGenerator generator =
                    new UrlGenerator("https://example.com", "/{WORD}/admin/{WORD}");

            assertThat(generator.generateUrl("api"))
                    .isEqualTo("https://example.com/api/admin/api");
        }

        @Test
        void generateUrl_patternCanIncludeSuffix() {
            UrlGenerator generator =
                    new UrlGenerator("https://example.com", "/api/{WORD}/users.json");

            assertThat(generator.generateUrl("v2"))
                    .isEqualTo("https://example.com/api/v2/users.json");
        }

        @Test
        void generateUrl_patternEntryStillEncoded() {
            UrlGenerator generator = new UrlGenerator("https://example.com", "/q/{WORD}");

            assertThat(generator.generateUrl("hello world"))
                    .isEqualTo("https://example.com/q/hello%20world");
        }
    }

    @Nested
    class LengthAndValidation {

        @Test
        void generateUrl_atMaxLength_succeeds() {
            UrlGenerator generator = new UrlGenerator("https://example.com");
            int basePrefix = "https://example.com/".length();
            String entry = "a".repeat(UrlGenerator.MAX_URL_LENGTH - basePrefix);

            String url = generator.generateUrl(entry);

            assertThat(url).hasSize(UrlGenerator.MAX_URL_LENGTH);
        }

        @Test
        void generateUrl_aboveMaxLength_throwsIllegalArgumentException() {
            UrlGenerator generator = new UrlGenerator("https://example.com");
            String entry = "a".repeat(UrlGenerator.MAX_URL_LENGTH + 1);

            assertThatThrownBy(() -> generator.generateUrl(entry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum length");
        }

        @Test
        void generateUrl_rawEntryAboveMaxLength_isRejectedBeforeBuildingUrl() {
            // Architect-added pre-check: the cap is also applied to the raw entry
            // length so a pathological wordlist line cannot force allocation of a
            // multi-megabyte combined URL string just to fail validation later.
            // Exercise it with an entry comfortably larger than the cap.
            UrlGenerator generator = new UrlGenerator("https://example.com");
            String entry = "a".repeat(UrlGenerator.MAX_URL_LENGTH * 4);

            assertThatThrownBy(() -> generator.generateUrl(entry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum length");
        }

        @Test
        void generateUrl_postBuildLengthCheck_rejectsWhenEntryFitsButResultDoesnt() {
            // Cover the OTHER branch of the length check: an entry whose own
            // length is <= MAX_URL_LENGTH but whose combined URL exceeds it
            // (here the base prefix pushes the total over the cap).
            UrlGenerator generator = new UrlGenerator("https://example.com/some/longer/prefix");
            String entry = "a".repeat(UrlGenerator.MAX_URL_LENGTH);

            assertThatThrownBy(() -> generator.generateUrl(entry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum length");
        }
    }

    @Nested
    class RequestFactory {

        @Test
        void generateRequest_returnsScanRequestWithExpectedFields() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            ScanRequest request = generator.generateRequest("admin");

            assertThat(request.url()).isEqualTo("https://example.com/admin");
            assertThat(request.wordlistEntry()).isEqualTo("admin");
            assertThat(request.baseUrl()).isEqualTo("https://example.com");
            assertThat(request.id()).isNotNull();
        }

        @Test
        void generateRequest_assignsUniqueIds() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            ScanRequest first = generator.generateRequest("a");
            ScanRequest second = generator.generateRequest("a");

            assertThat(first.id()).isNotEqualTo(second.id());
        }

        @Test
        void generateRequests_withNullStream_throwsNullPointerException() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            assertThatThrownBy(() -> generator.generateRequests(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void generateRequests_mapsEachEntryToScanRequestPreservingOrder() {
            // Architect-added bulk API: a stream-in / stream-out variant that
            // composes with WordlistService.readWordlist(). Verify mapping is
            // 1:1 and order-preserving, since callers rely on positional
            // correlation with the source wordlist.
            UrlGenerator generator = new UrlGenerator("https://example.com", "/api/{WORD}");

            List<ScanRequest> requests = generator
                    .generateRequests(Stream.of("alpha", "bravo", "charlie"))
                    .toList();

            assertThat(requests).extracting(ScanRequest::url).containsExactly(
                    "https://example.com/api/alpha",
                    "https://example.com/api/bravo",
                    "https://example.com/api/charlie");
            assertThat(requests).extracting(ScanRequest::wordlistEntry)
                    .containsExactly("alpha", "bravo", "charlie");
            assertThat(requests).extracting(ScanRequest::id).doesNotHaveDuplicates();
        }

        @Test
        void generateRequests_withEmptyStream_returnsEmptyResult() {
            UrlGenerator generator = new UrlGenerator("https://example.com");

            List<ScanRequest> requests = generator
                    .generateRequests(Stream.<String>empty())
                    .toList();

            assertThat(requests).isEmpty();
        }

        @Test
        void generateRequests_propagatesExceptionsFromInvalidEntries() {
            // Documented contract: malformed entries fail-fast rather than being
            // silently dropped. Verify the bulk API surfaces the same exception
            // as generateRequest() rather than swallowing it.
            UrlGenerator generator = new UrlGenerator("https://example.com");

            // toList() forces terminal evaluation, triggering the lazy map.
            assertThatThrownBy(() -> generator
                    .generateRequests(Stream.of("ok", "bad\nentry"))
                    .toList())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control character");
        }

        @Test
        void generateRequests_isLazy_doesNotInvokePerEntryUntilTerminal() {
            // The stream returned should be lazy. We verify this by handing in a
            // stream containing an invalid entry but never running a terminal op
            // — if mapping were eager, we'd see an exception thrown from the
            // generateRequests() call itself.
            UrlGenerator generator = new UrlGenerator("https://example.com");

            Stream<ScanRequest> lazy = generator
                    .generateRequests(Stream.of("ok", "bad\nentry"));

            // Closing the stream without consuming it must not invoke the mapper.
            lazy.close();
        }
    }

    @Nested
    class ThreadSafety {

        @Test
        void generateUrl_isSafeForConcurrentUse() throws Exception {
            UrlGenerator generator = new UrlGenerator("https://example.com", "/api/{WORD}");
            int concurrency = 64;
            int perThread = 250;

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
                for (int i = 0; i < concurrency; i++) {
                    final int threadIndex = i;
                    futures.add(executor.submit(() -> {
                        for (int j = 0; j < perThread; j++) {
                            String expected = "https://example.com/api/t" + threadIndex + "-" + j;
                            String url = generator.generateUrl("t" + threadIndex + "-" + j);
                            // Strong per-call assertion: the result must match the
                            // exact URL expected for this thread's input. Earlier
                            // version only checked startsWith(), which would pass
                            // even if calls returned cross-contaminated results.
                            assertThat(url).isEqualTo(expected);
                        }
                        return null;
                    }));
                }
                for (var f : futures) {
                    f.get(2, TimeUnit.SECONDS);
                }
            }
        }
    }
}
