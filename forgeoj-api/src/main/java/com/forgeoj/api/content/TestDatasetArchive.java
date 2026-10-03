/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Parses a bounded private upload without extracting archive paths or executing content. */
public final class TestDatasetArchive {
    public static final int MAX_PAIRS = 100;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("([0-9]{3})\\.(in|out)");

    private TestDatasetArchive() {}

    public record TestPair(int number, String input, String expectedOutput) {}

    public static List<TestPair> parse(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw invalid();
        }
        Path temporary = null;
        try {
            // ZipFile validates the central directory. The random temporary file is not an
            // extracted entry; no name supplied by the archive is ever used as a filesystem path.
            temporary = Files.createTempFile("forgeoj-test-import-", ".zip");
            Files.write(temporary, archive);
            try (ZipFile zip = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
                Map<Integer, Map<String, String>> pairs = new TreeMap<>();
                var entries = zip.entries();
                int files = 0;
                long total = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    var name = NAME.matcher(entry.getName());
                    if (++files > MAX_PAIRS * 2 || entry.isDirectory() || !name.matches()) {
                        throw invalid();
                    }
                    int number = Integer.parseInt(name.group(1));
                    if (number == 0 || entry.getSize() < 0 || entry.getSize() > MAX_FILE_BYTES) {
                        throw invalid();
                    }
                    Map<String, String> pair = pairs.computeIfAbsent(number, ignored -> new HashMap<>());
                    if (pair.containsKey(name.group(2))) throw invalid();
                    byte[] bytes;
                    try (InputStream stream = zip.getInputStream(entry)) {
                        bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
                        if (bytes.length > MAX_FILE_BYTES || stream.read() != -1 || bytes.length != entry.getSize()) {
                            throw invalid();
                        }
                    }
                    total += bytes.length;
                    if (total > MAX_TOTAL_BYTES) throw invalid();
                    // ZipFile's inflater does not guarantee CRC checking, so compare it ourselves.
                    var crc = new java.util.zip.CRC32();
                    crc.update(bytes);
                    if (crc.getValue() != entry.getCrc()) throw invalid();
                    pair.put(name.group(2), utf8(bytes));
                }
                if (pairs.isEmpty() || pairs.size() > MAX_PAIRS) throw invalid();
                List<TestPair> result = new ArrayList<>(pairs.size());
                for (var pair : pairs.entrySet()) {
                    if (!pair.getValue().keySet().equals(java.util.Set.of("in", "out"))) throw invalid();
                    result.add(new TestPair(pair.getKey(), pair.getValue().get("in"), pair.getValue().get("out")));
                }
                return List.copyOf(result);
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Caller receives a fixed error, never an archive path or decompression exception.
            throw invalid();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    throw new IllegalStateException("Test archive temporary cleanup failed");
                }
            }
        }
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_TEST_ARCHIVE");
    }
}
