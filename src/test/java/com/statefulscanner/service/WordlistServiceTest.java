package com.statefulscanner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class WordlistServiceTest {

    @TempDir
    Path tempDir;

    private WordlistService service;
    private Stream<String> openStream;

    @BeforeEach
    void setUp() {
        service = new WordlistService();
    }

    @AfterEach
    void tearDown() {
        if (openStream != null) {
            openStream.close();
        }
    }

    private Path writeWordlistFile(String... lines) throws IOException {
        Path file = Files.createTempFile(tempDir, "wordlist", ".txt");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    // --- Input Validation ---

    @Test
    void readWordlist_withNullFilePath_throwsNullPointerException() {
        assertThatThrownBy(() -> service.readWordlist(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void readWordlist_withNonExistentFile_throwsIllegalArgumentException() {
        String nonExistent = tempDir.resolve("nonexistent.txt").toString();

        assertThatThrownBy(() -> service.readWordlist(nonExistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wordlist file not found");
    }

    @Test
    void readWordlist_withDirectoryPath_throwsIllegalArgumentException() {
        String dirPath = tempDir.toString();

        assertThatThrownBy(() -> service.readWordlist(dirPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path is a directory, not a file");
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    void readWordlist_withUnreadableFile_throwsIllegalArgumentException() throws IOException {
        Path file = writeWordlistFile("content");
        file.toFile().setReadable(false);

        assertThatThrownBy(() -> service.readWordlist(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not readable");
    }

    // --- Basic Reading ---

    @Test
    void readWordlist_withValidFile_returnsAllLines() throws IOException {
        Path file = writeWordlistFile("alpha", "bravo", "charlie");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    void readWordlist_withEmptyFile_returnsEmptyStream() throws IOException {
        Path file = Files.createTempFile(tempDir, "empty", ".txt");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).isEmpty();
    }

    @Test
    void readWordlist_preservesOriginalOrder() throws IOException {
        Path file = writeWordlistFile("zebra", "apple", "mango", "banana");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("zebra", "apple", "mango", "banana");
    }

    // --- Line Filtering ---

    @Test
    void readWordlist_trimsLeadingAndTrailingWhitespace() throws IOException {
        Path file = writeWordlistFile("  padded  ", "\tword\t", "  spaced");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("padded", "word", "spaced");
    }

    @Test
    void readWordlist_filtersOutEmptyLines() throws IOException {
        Path file = writeWordlistFile("alpha", "", "bravo", "", "", "charlie");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    void readWordlist_filtersOutCommentLines() throws IOException {
        Path file = writeWordlistFile("# this is a comment", "alpha", "#another", "bravo");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("alpha", "bravo");
    }

    @Test
    void readWordlist_doesNotFilterLinesWithHashInMiddle() throws IOException {
        Path file = writeWordlistFile("word#notacomment", "C#sharp");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("word#notacomment", "C#sharp");
    }

    @Test
    void readWordlist_commentLineWithLeadingWhitespace_isFiltered() throws IOException {
        Path file = writeWordlistFile("   # indented comment", "valid");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("valid");
    }

    @Test
    void readWordlist_filtersWhitespaceOnlyLines() throws IOException {
        Path file = writeWordlistFile("   ", "\t\t", "  \t  ", "valid");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"# comment", "#comment", "#", "   #indented"})
    void readWordlist_commentVariations_areFiltered(String commentLine) throws IOException {
        Path file = writeWordlistFile(commentLine, "valid");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\t", "\t  \t"})
    void readWordlist_blankLineVariations_areFiltered(String blankLine) throws IOException {
        Path file = writeWordlistFile(blankLine, "valid");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("valid");
    }

    // --- Duplicate Removal ---

    @Test
    void readWordlist_withRemoveDuplicatesFalse_preservesDuplicates() throws IOException {
        Path file = writeWordlistFile("apple", "banana", "apple", "cherry", "banana");

        openStream = service.readWordlist(file.toString(), false);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("apple", "banana", "apple", "cherry", "banana");
    }

    @Test
    void readWordlist_withRemoveDuplicatesTrue_removesDuplicateLines() throws IOException {
        Path file = writeWordlistFile("apple", "banana", "apple", "cherry", "banana");

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("apple", "banana", "cherry");
    }

    @Test
    void readWordlist_withRemoveDuplicatesTrue_isCaseSensitive() throws IOException {
        Path file = writeWordlistFile("Word", "word", "WORD");

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("Word", "word", "WORD");
    }

    @Test
    void readWordlist_withRemoveDuplicatesTrue_deduplicatesAfterTrimming() throws IOException {
        Path file = writeWordlistFile("  hello", "hello  ", "hello");

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("hello");
    }

    @Test
    void readWordlist_withRemoveDuplicatesTrue_allLinesSame_returnsSingleEntry() throws IOException {
        Path file = writeWordlistFile("repeat", "repeat", "repeat", "repeat", "repeat");

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("repeat");
    }

    @Test
    void readWordlist_withRemoveDuplicatesTrue_emptyFile_returnsEmptyStream() throws IOException {
        Path file = Files.createTempFile(tempDir, "empty", ".txt");

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).isEmpty();
    }

    @Test
    void readWordlist_singleArgOverload_doesNotRemoveDuplicates() throws IOException {
        Path file = writeWordlistFile("dup", "dup", "dup");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("dup", "dup", "dup");
    }

    // --- Stream Resource Management ---

    @Test
    void readWordlist_streamCanBeClosedMultipleTimes() throws IOException {
        Path file = writeWordlistFile("alpha", "bravo");

        openStream = service.readWordlist(file.toString());
        openStream.close();
        openStream.close(); // second close should be idempotent
    }

    @Test
    void readWordlist_streamConsumedTwice_throwsIllegalStateException() throws IOException {
        Path file = writeWordlistFile("alpha", "bravo");

        openStream = service.readWordlist(file.toString());
        openStream.toList();

        assertThatThrownBy(() -> openStream.toList())
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Edge Cases ---

    @Test
    void readWordlist_withSingleLineFile_returnsSingleEntry() throws IOException {
        Path file = writeWordlistFile("onlyone");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("onlyone");
    }

    @Test
    void readWordlist_withSingleCommentLine_returnsEmptyStream() throws IOException {
        Path file = writeWordlistFile("# just a comment");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).isEmpty();
    }

    @Test
    void readWordlist_withSingleEmptyLine_returnsEmptyStream() throws IOException {
        Path file = writeWordlistFile("");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).isEmpty();
    }

    @Test
    void readWordlist_lineContainingOnlyHash_isFiltered() throws IOException {
        Path file = writeWordlistFile("#", "valid");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("valid");
    }

    @Test
    void readWordlist_withUnicodeContent_readsCorrectly() throws IOException {
        Path file = writeWordlistFile("cafe\u0301", "nai\u0308ve", "\u65E5\u672C\u8A9E", "\u00FCber");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("cafe\u0301", "nai\u0308ve", "\u65E5\u672C\u8A9E", "\u00FCber");
    }

    @Test
    void readWordlist_withWindowsLineEndings_handledCorrectly() throws IOException {
        Path file = Files.createTempFile(tempDir, "crlf", ".txt");
        Files.writeString(file, "alpha\r\nbravo\r\ncharlie\r\n", StandardCharsets.UTF_8);

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    void readWordlist_withVeryLongLines_handledCorrectly() throws IOException {
        String longLine = "a".repeat(10_001);
        Path file = writeWordlistFile(longLine, "short");

        openStream = service.readWordlist(file.toString());
        List<String> result = openStream.toList();

        assertThat(result).containsExactly(longLine, "short");
    }

    // --- Mixed Content (Integration-style) ---

    @Test
    void readWordlist_withMixedContent_appliesAllFiltersCorrectly() throws IOException {
        Path file = writeWordlistFile(
                "# Wordlist header",
                "",
                "admin",
                "  password  ",
                "# another comment",
                "admin",
                "   ",
                "root",
                "  password",
                "\t#indented-comment",
                "guest"
        );

        openStream = service.readWordlist(file.toString(), true);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("admin", "password", "root", "guest");
    }

    // --- Large File Streaming ---

    @Test
    void readWordlist_withLargeFile_streamsCorrectly() throws IOException {
        Path file = Files.createTempFile(tempDir, "large", ".txt");
        List<String> lines = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(i -> "entry_" + i)
                .toList();
        Files.write(file, lines, StandardCharsets.UTF_8);

        openStream = service.readWordlist(file.toString());
        long count = openStream.count();

        assertThat(count).isEqualTo(10_000);
    }

    @Test
    void readWordlist_withLargeFile_doesNotLoadEntireFileIntoMemory() throws IOException {
        Path file = Files.createTempFile(tempDir, "huge", ".txt");
        int lineCount = 500_000;
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < lineCount; i++) {
                writer.write("entry_" + i);
                writer.newLine();
            }
        }

        long memoryBefore = usedMemory();

        try (Stream<String> stream = service.readWordlist(file.toString())) {
            // consume the stream entry-by-entry via count (does not retain elements)
            long count = stream.count();
            long memoryDuring = usedMemory();

            assertThat(count).isEqualTo(lineCount);
            // Memory growth should be well under the full file size.
            // 500K lines of ~12 chars each = ~6MB if loaded. Allow 2MB headroom for GC noise.
            long memoryGrowth = memoryDuring - memoryBefore;
            assertThat(memoryGrowth).isLessThan(4_000_000L);
        }
    }

    // --- CSV Support ---

    @Test
    void readWordlistFromCsv_extractsCorrectColumn() throws IOException {
        Path file = writeWordlistFile("admin,password,role", "user,secret,viewer");

        openStream = service.readWordlistFromCsv(file.toString(), 1);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("password", "secret");
    }

    @Test
    void readWordlistFromCsv_firstColumn() throws IOException {
        Path file = writeWordlistFile("admin,password", "root,toor");

        openStream = service.readWordlistFromCsv(file.toString(), 0);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("admin", "root");
    }

    @Test
    void readWordlistFromCsv_skipsLinesWithFewerColumns() throws IOException {
        Path file = writeWordlistFile("admin,password,role", "incomplete", "user,secret,viewer");

        openStream = service.readWordlistFromCsv(file.toString(), 2);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("role", "viewer");
    }

    @Test
    void readWordlistFromCsv_filtersCommentsAndBlanks() throws IOException {
        Path file = writeWordlistFile("# header", "", "admin,password", "user,secret");

        openStream = service.readWordlistFromCsv(file.toString(), 0);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("admin", "user");
    }

    @Test
    void readWordlistFromCsv_withNegativeColumnIndex_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.readWordlistFromCsv("any", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnIndex must not be negative");
    }

    @Test
    void readWordlistFromCsv_trimsColumnValues() throws IOException {
        Path file = writeWordlistFile("  admin , password ", "user, secret");

        openStream = service.readWordlistFromCsv(file.toString(), 1);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("password", "secret");
    }

    @Test
    void readWordlistFromCsv_skipsEmptyColumnValues() throws IOException {
        Path file = writeWordlistFile("admin,", "user,secret");

        openStream = service.readWordlistFromCsv(file.toString(), 1);
        List<String> result = openStream.toList();

        assertThat(result).containsExactly("secret");
    }

    // --- Count Entries ---

    @Test
    void countWordlistEntries_returnsCorrectCount() throws IOException {
        Path file = writeWordlistFile("alpha", "# comment", "", "bravo", "charlie");

        long count = service.countWordlistEntries(file.toString());

        assertThat(count).isEqualTo(3);
    }

    @Test
    void countWordlistEntries_emptyFile_returnsZero() throws IOException {
        Path file = Files.createTempFile(tempDir, "empty", ".txt");

        long count = service.countWordlistEntries(file.toString());

        assertThat(count).isZero();
    }

    @Test
    void countWordlistEntries_allComments_returnsZero() throws IOException {
        Path file = writeWordlistFile("# one", "# two", "# three");

        long count = service.countWordlistEntries(file.toString());

        assertThat(count).isZero();
    }

    // --- Helper ---

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
