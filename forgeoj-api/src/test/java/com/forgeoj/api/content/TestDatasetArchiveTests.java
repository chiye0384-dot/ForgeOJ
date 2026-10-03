/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class TestDatasetArchiveTests {
    @Test void sortsNumbersAndPreservesExactUtf8AndLineEndings() throws Exception {
        var files = new LinkedHashMap<String,byte[]>();
        files.put("010.out", bytes("中文\r\n")); files.put("002.in", bytes("1 2\n"));
        files.put("010.in", bytes("")); files.put("002.out", bytes("3\n"));
        var result = TestDatasetArchive.parse(zip(files));
        assertThat(result).containsExactly(new TestDatasetArchive.TestPair(2,"1 2\n","3\n"),
                new TestDatasetArchive.TestPair(10,"","中文\r\n"));
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsPathsDirectoriesScriptsAndUnsupportedNames() throws Exception {
        for (String name : new String[]{"../001.in","/001.in","nested/001.in","nested\\001.in", "001/", "000.in","1.in","001.IN","generate.java"}) {
            reject(zip(Map.of(name,bytes("fixture"),"001.out",bytes("answer"))));
        }
    }
    @Test void rejectsUnpairedEmptyAndInvalidZip() throws Exception {
        reject(zip(Map.of("001.in",bytes("input")))); reject(zip(Map.of("001.out",bytes("output"))));
        reject(zip(Map.of())); reject(bytes("not a zip")); reject(new byte[0]); reject(null);
    }
    @Test void rejectsMalformedUtf8InsteadOfReplacementCharacters() throws Exception {
        reject(zip(Map.of("001.in",new byte[]{(byte)0xc3,0x28},"001.out",bytes(""))));
    }
    @Test void boundsActualExpansionAndAcceptsExactFileBoundary() throws Exception {
        byte[] exact = new byte[TestDatasetArchive.MAX_FILE_BYTES]; java.util.Arrays.fill(exact,(byte)'x');
        assertThat(TestDatasetArchive.parse(zip(Map.of("001.in",exact,"001.out",bytes(""))))).hasSize(1);
        reject(zip(Map.of("001.in",java.util.Arrays.copyOf(exact,exact.length+1),"001.out",bytes(""))));
        reject(new byte[TestDatasetArchive.MAX_ARCHIVE_BYTES+1]);
    }
    @Test void boundsTotalUncompressedBytes() throws Exception {
        var files = new LinkedHashMap<String,byte[]>();byte[] data=new byte[TestDatasetArchive.MAX_FILE_BYTES];java.util.Arrays.fill(data,(byte)'a');
        for(int i=1;i<=8;i++) { files.put("%03d.in".formatted(i),data); files.put("%03d.out".formatted(i),data); }
        assertThat(TestDatasetArchive.parse(zip(files))).hasSize(8);
        files.put("009.in",bytes("x")); files.put("009.out",bytes(""));reject(zip(files));
    }
    @Test void boundsPairsAndEntryCount() throws Exception {
        var files=new LinkedHashMap<String,byte[]>();for(int i=1;i<=100;i++) { files.put("%03d.in".formatted(i),bytes(""));files.put("%03d.out".formatted(i),bytes("")); }
        assertThat(TestDatasetArchive.parse(zip(files))).hasSize(100);
        files.put("101.in",bytes(""));files.put("101.out",bytes(""));reject(zip(files));
    }
    @Test void rejectsDuplicateEntriesUsingCentralDirectoryNames() throws Exception {
        byte[] archive=zip(Map.of("001.in",bytes("a"),"001.out",bytes("b"),"002.in",bytes("c"),"002.out",bytes("d")));
        // Same-length rename in both local/central headers produces duplicate names without
        // relying on ZipOutputStream, which rejects duplicates at generation time.
        replace(archive,bytes("002.in"),bytes("001.in"));reject(archive);
    }
    @Test void rejectsCrcMismatchAndTruncatedCentralDirectory() throws Exception {
        byte[] archive=storedZip();byte[] bad=archive.clone();
        // Mutate the plain stored payload while preserving its CRC/header and byte lengths.
        replace(bad,bytes("ORIGINAL_PAYLOAD"),bytes("MODIFIED_PAYLOAD"));reject(bad);
        reject(java.util.Arrays.copyOf(archive,archive.length-12));
    }
    private static void reject(byte[] archive) {
        assertThatThrownBy(() -> TestDatasetArchive.parse(archive)).isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_TEST_ARCHIVE");
    }
    private static byte[] bytes(String s) {return s.getBytes(StandardCharsets.UTF_8);}
    private static byte[] zip(Map<String,byte[]> files) throws Exception {
        var result=new ByteArrayOutputStream();try(var stream=new ZipOutputStream(result,StandardCharsets.UTF_8)) {
            for(var file:files.entrySet()) {stream.putNextEntry(new ZipEntry(file.getKey()));stream.write(file.getValue());stream.closeEntry();}
        }return result.toByteArray();
    }
    private static byte[] storedZip() throws Exception {
        var result=new ByteArrayOutputStream();try(var stream=new ZipOutputStream(result)) {
            for(String extension:new String[]{"in","out"}) {byte[] data=bytes("ORIGINAL_PAYLOAD");var crc=new java.util.zip.CRC32();crc.update(data);
                var entry=new ZipEntry("001."+extension);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCrc(crc.getValue());stream.putNextEntry(entry);stream.write(data);stream.closeEntry();}
        }return result.toByteArray();
    }
    private static void replace(byte[] archive,byte[] before,byte[] after) {
        assertThat(before.length).isEqualTo(after.length);
        for(int i=0;i<=archive.length-before.length;i++) {boolean match=true;for(int j=0;j<before.length;j++) if(archive[i+j]!=before[j]) {match=false;break;}
            if(match) System.arraycopy(after,0,archive,i,after.length);}
    }
}
