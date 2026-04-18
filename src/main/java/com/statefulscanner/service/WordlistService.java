package com.statefulscanner.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Service for reading and streaming wordlist files line-by-line.
 *
 * <p>All methods that return a {@link Stream} require the caller to close the stream
 * when done (preferably via try-with-resources) to release the underlying file handle.</p>
 *
 * <p><strong>Security note:</strong> This service accepts caller-supplied file paths without
 * canonical-path or path-traversal checks. It is intended for internal use only. If an endpoint
 * ever forwards user-supplied input here, a path validation layer must be added first.</p>
 */
@Service
public class WordlistService {

    /**
     * Reads a wordlist file, returning a lazily-evaluated stream of entries.
     * Blank lines, comment lines (starting with {@code #}), and surrounding
     * whitespace are filtered/trimmed automatically.
     *
     * @param filePath path to the wordlist file (must not be {@code null})
     * @return a stream of wordlist entries; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if the path does not point to a readable file
     */
    public Stream<String> readWordlist(String filePath) throws IOException {
        return readWordlist(filePath, false);
    }

    /**
     * Reads a wordlist file with optional duplicate removal.
     *
     * <p><strong>Memory note:</strong> When {@code removeDuplicates} is {@code true},
     * an in-memory {@link HashSet} retains every distinct entry for the lifetime of the
     * stream — O(n) memory. On large wordlists (e.g. 1M+ entries) this partly defeats
     * the streaming design. Use with caution on memory-constrained workloads.
     *
     * @param filePath         path to the wordlist file (must not be {@code null})
     * @param removeDuplicates if {@code true}, duplicate entries are suppressed
     *                         (first occurrence is kept, case-sensitive)
     * @return a stream of wordlist entries; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if the path does not point to a readable file
     */
    public Stream<String> readWordlist(String filePath, boolean removeDuplicates) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Wordlist file not found: " + filePath);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is a directory, not a file: " + filePath);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Wordlist file not readable: " + filePath);
        }

        BufferedReader reader;
        try {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Failed to open wordlist file: " + filePath, e);
        }

        Stream<String> stream = reader.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"));

        if (removeDuplicates) {
            Set<String> seenEntries = new HashSet<>();
            stream = stream.filter(seenEntries::add);
        }

        return stream.onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close wordlist reader", e);
            }
        });
    }

    /**
     * Reads a CSV wordlist file, extracting entries from a specific column.
     * Each line is split on comma and the value at {@code columnIndex} is used
     * as the entry. Lines with fewer columns than required are skipped.
     *
     * <p><strong>Limitation:</strong> Uses simple comma-split; embedded commas
     * and quoted values (e.g. {@code "last,first",role}) are not supported.
     *
     * @param filePath    path to the CSV wordlist file
     * @param columnIndex zero-based index of the column to extract
     * @return a stream of entries from the specified column; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if columnIndex is negative or file is invalid
     */
    public Stream<String> readWordlistFromCsv(String filePath, int columnIndex) throws IOException {
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must not be negative: " + columnIndex);
        }

        return readWordlist(filePath)
                .map(line -> line.split(",", -1))
                .filter(cols -> cols.length > columnIndex)
                .map(cols -> cols[columnIndex].trim())
                .filter(value -> !value.isEmpty());
    }

    /**
     * Counts the number of valid entries in a wordlist file.
     * Blank lines and comment lines are excluded from the count.
     *
     * @param filePath path to the wordlist file
     * @return the number of valid entries
     * @throws IOException if the file cannot be opened or read
     */
    public long countWordlistEntries(String filePath) throws IOException {
        try (Stream<String> stream = readWordlist(filePath)) {
            return stream.count();
        }
    }
}
