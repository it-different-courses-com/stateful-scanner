package com.statefulscanner.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class ScanRequestTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String URL = "https://example.com/admin";
    private static final String ENTRY = "admin";
    private static final String BASE_URL = "https://example.com";

    @Nested
    class CompactConstructorValidation {

        @Test
        void constructor_withNullId_throwsNullPointerException() {
            assertThatThrownBy(() -> new ScanRequest(null, URL, ENTRY, BASE_URL))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("id");
        }

        @Test
        void constructor_withNullUrl_throwsNullPointerException() {
            assertThatThrownBy(() -> new ScanRequest(ID, null, ENTRY, BASE_URL))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("url");
        }

        @Test
        void constructor_withNullWordlistEntry_throwsNullPointerException() {
            assertThatThrownBy(() -> new ScanRequest(ID, URL, null, BASE_URL))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("wordlistEntry");
        }

        @Test
        void constructor_withNullBaseUrl_throwsNullPointerException() {
            assertThatThrownBy(() -> new ScanRequest(ID, URL, ENTRY, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("baseUrl");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void constructor_withBlankUrl_throwsIllegalArgumentException(String blankUrl) {
            assertThatThrownBy(() -> new ScanRequest(ID, blankUrl, ENTRY, BASE_URL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void constructor_withBlankWordlistEntry_isAccepted(String blankEntry) {
            // Deliberate, documented contract: unlike url, wordlistEntry is NOT
            // blank-checked — blank-rejection is the producing layer's job (see
            // UrlGenerator.generateUrl). Locking this in means a later "make it
            // consistent with url" change cannot silently tighten the contract.
            ScanRequest request = new ScanRequest(ID, URL, blankEntry, BASE_URL);

            assertThat(request.wordlistEntry()).isEqualTo(blankEntry);
        }

        @Test
        void constructor_withValidArguments_populatesEveryComponent() {
            ScanRequest request = new ScanRequest(ID, URL, ENTRY, BASE_URL);

            assertThat(request.id()).isEqualTo(ID);
            assertThat(request.url()).isEqualTo(URL);
            assertThat(request.wordlistEntry()).isEqualTo(ENTRY);
            assertThat(request.baseUrl()).isEqualTo(BASE_URL);
        }
    }

    @Nested
    class Factory {

        @Test
        void of_assignsFreshNonNullId() {
            ScanRequest request = ScanRequest.of(URL, ENTRY, BASE_URL);

            assertThat(request.id()).isNotNull();
        }

        @Test
        void of_assignsUniqueIdPerCall() {
            ScanRequest first = ScanRequest.of(URL, ENTRY, BASE_URL);
            ScanRequest second = ScanRequest.of(URL, ENTRY, BASE_URL);

            assertThat(first.id()).isNotEqualTo(second.id());
        }

        @Test
        void of_passesThroughUrlEntryAndBaseUrl() {
            ScanRequest request = ScanRequest.of(URL, ENTRY, BASE_URL);

            assertThat(request.url()).isEqualTo(URL);
            assertThat(request.wordlistEntry()).isEqualTo(ENTRY);
            assertThat(request.baseUrl()).isEqualTo(BASE_URL);
        }

        @Test
        void of_isNotABackDoorAroundValidation() {
            // The factory delegates to the compact constructor, so the same
            // validation must fire: a blank url still fails, a null url still NPEs.
            assertThatThrownBy(() -> ScanRequest.of("  ", ENTRY, BASE_URL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
            assertThatThrownBy(() -> ScanRequest.of(null, ENTRY, BASE_URL))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("url");
        }
    }

    @Nested
    class Equality {

        @Test
        void equals_isValueBasedWhenEveryComponentMatches() {
            ScanRequest a = new ScanRequest(ID, URL, ENTRY, BASE_URL);
            ScanRequest b = new ScanRequest(ID, URL, ENTRY, BASE_URL);

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void equals_differsWhenOnlyIdDiffers() {
            // Documented intent: two requests for the same URL are NOT equal when
            // their ids differ — the id correlates one specific in-flight attempt
            // with its eventual response, so it must participate in equality.
            ScanRequest a = new ScanRequest(UUID.randomUUID(), URL, ENTRY, BASE_URL);
            ScanRequest b = new ScanRequest(UUID.randomUUID(), URL, ENTRY, BASE_URL);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
