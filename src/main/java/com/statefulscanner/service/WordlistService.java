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
    public Stream<String> readWordlist(Path filePath) throws IOException {
        return readWordlist(filePath, false);
    }

    /** Convenience overload accepting a {@code String} path. */
    public Stream<String> readWordlist(String filePath) throws IOException {
        return readWordlist(Path.of(filePath), false);
    }

    /** Convenience overload accepting a {@code String} path with duplicate control. */
    public Stream<String> readWordlist(String filePath, boolean removeDuplicates) throws IOException {
        return readWordlist(Path.of(filePath), removeDuplicates);
    }

    /**
     * Reads a wordlist file with optional duplicate removal.
     *
     * @param filePath         path to the wordlist file (must not be {@code null})
     * @param removeDuplicates if {@code true}, duplicate entries are suppressed
     *                         (first occurrence is kept, case-sensitive)
     * @return a stream of wordlist entries; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if the path does not point to a readable file
     */
    public Stream<String> readWordlist(Path filePath, boolean removeDuplicates) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Wordlist file not found: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Path is a directory, not a file: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            throw new IllegalArgumentException("Wordlist file not readable: " + filePath);
        }

        BufferedReader reader;
        try {
            reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
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
     * @param filePath    path to the CSV wordlist file
     * @param columnIndex zero-based index of the column to extract
     * @return a stream of entries from the specified column; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if columnIndex is negative or file is invalid
     */
    public Stream<String> readWordlistFromCsv(String filePath, int columnIndex) throws IOException {
        return readWordlistFromCsv(Path.of(filePath), columnIndex);
    }

    /**
     * Reads a CSV wordlist file, extracting entries from a specific column.
     *
     * @param filePath    path to the CSV wordlist file
     * @param columnIndex zero-based index of the column to extract
     * @return a stream of entries from the specified column; the caller must close the stream
     * @throws IOException if the file cannot be opened or read
     * @throws IllegalArgumentException if columnIndex is negative or file is invalid
     */
    public Stream<String> readWordlistFromCsv(Path filePath, int columnIndex) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (columnIndex < 0) {
            throw new IllegalArgumentException("columnIndex must not be negative: " + columnIndex);
        }

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Wordlist file not found: " + filePath);
        }
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("Path is a directory, not a file: " + filePath);
        }
        if (!Files.isReadable(filePath)) {
            throw new IllegalArgumentException("Wordlist file not readable: " + filePath);
        }

        BufferedReader reader;
        try {
            reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Failed to open wordlist file: " + filePath, e);
        }

        Stream<String> stream = reader.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.split(",", -1).length > columnIndex)
                .map(line -> line.split(",", -1)[columnIndex].trim())
                .filter(value -> !value.isEmpty());

        return stream.onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close wordlist reader", e);
            }
        });
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
        return countWordlistEntries(Path.of(filePath));
    }

    /**
     * Counts the number of valid entries in a wordlist file.
     * Blank lines and comment lines are excluded from the count.
     *
     * @param filePath path to the wordlist file
     * @return the number of valid entries
     * @throws IOException if the file cannot be opened or read
     */
    public long countWordlistEntries(Path filePath) throws IOException {
        try (Stream<String> stream = readWordlist(filePath)) {
            return stream.count();
        }
    }
}
