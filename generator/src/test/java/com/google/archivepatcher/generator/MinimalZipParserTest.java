// Copyright 2016 Google LLC. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.archivepatcher.generator;

import static com.google.common.truth.Truth.assertThat;

import com.google.archivepatcher.shared.UnitTestZipArchive;
import com.google.archivepatcher.shared.UnitTestZipEntry;
import com.google.archivepatcher.shared.bytesource.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link MinimalZipParser}.
 */
@RunWith(JUnit4.class)
@SuppressWarnings("javadoc")
public class MinimalZipParserTest {
  private byte[] unitTestZipArchive;

  @Before
  public void setup() throws Exception {
    unitTestZipArchive = UnitTestZipArchive.makeTestZip();
  }

  private void checkExpectedBytes(byte[] expectedData, int unitTestZipArchiveOffset) {
    for (int index = 0; index < 4; index++) {
      byte actualByte = unitTestZipArchive[unitTestZipArchiveOffset + index];
      assertThat(actualByte).isEqualTo(expectedData[index]);
    }
  }

  @Test
  public void testLocateStartOfEocd_WithArray() {
    int eocdOffset = MinimalZipParser.locateStartOfEocd(unitTestZipArchive);
    checkExpectedBytes(new byte[] {0x50, 0x4b, 0x05, 0x06}, eocdOffset);
  }

  @Test
  public void testLocateStartOfEocd_WithArray_NoEocd() {
    int eocdOffset = MinimalZipParser.locateStartOfEocd(new byte[32768]);
    assertThat(eocdOffset).isEqualTo(-1);
  }

  @Test
  public void testLocateStartOfEocd_WithFile() throws IOException {
    // Create a temp file with some zeroes, the EOCD header, and more zeroes.
    int bytesBefore = 53754;
    int bytesAfter = 107;
    File tempFile = File.createTempFile("MinimalZipParserTest", "zip");
    tempFile.deleteOnExit();
    try {
      FileOutputStream out = new FileOutputStream(tempFile);
      out.write(new byte[bytesBefore]);
      out.write(new byte[] {0x50, 0x4b, 0x05, 0x06});
      out.write(new byte[bytesAfter]);
      out.flush();
      out.close();
    } catch (IOException e) {
      try {
        tempFile.delete();
      } catch (Exception ignored) {
        // Nothing
      }
      throw e;
    }

    // Now expect to find the EOCD at the right place.
    try (ByteSource in = ByteSource.fromFile(tempFile)) {
      long eocdOffset = MinimalZipParser.locateStartOfEocd(in, 32768);
      assertThat(eocdOffset).isEqualTo(bytesBefore);
    }
  }

  @Test
  public void testLocateStartOfEocd_WithFile_NoEocd() throws IOException {
    // Create a temp file with some zeroes and no EOCD header at all
    File tempFile = File.createTempFile("MinimalZipParserTest", "zip");
    tempFile.deleteOnExit();
    try {
      FileOutputStream out = new FileOutputStream(tempFile);
      out.write(new byte[4000]);
      out.flush();
      out.close();
    } catch (IOException e) {
      try {
        tempFile.delete();
      } catch (Exception ignored) {
        // Nothing
      }
      throw e;
    }

    // Now expect to find no EOCD.
    try (ByteSource in = ByteSource.fromFile(tempFile)) {
      long eocdOffset = MinimalZipParser.locateStartOfEocd(in, 4000);
      assertThat(eocdOffset).isEqualTo(-1);
    }
  }

  @Test
  public void testParseEocd() throws IOException {
    int eocdOffset = MinimalZipParser.locateStartOfEocd(unitTestZipArchive);
    ByteArrayInputStream in = new ByteArrayInputStream(unitTestZipArchive);
    assertThat(in.skip(eocdOffset)).isEqualTo(eocdOffset);
    MinimalCentralDirectoryMetadata centralDirectoryMetadata = MinimalZipParser.parseEocd(in);
    assertThat(centralDirectoryMetadata).isNotNull();

    // Check that the central directory's first record is at the calculated offset
    //0x02014b50
    checkExpectedBytes(
        new byte[] {0x50, 0x4b, 0x01, 0x02},
        (int) centralDirectoryMetadata.getOffsetOfCentralDirectory());
    // Check that the central directory's length is correct, i.e. that the EOCD record follows it.
    long calculatedEndOfCentralDirectory =
        centralDirectoryMetadata.getOffsetOfCentralDirectory()
            + centralDirectoryMetadata.getLengthOfCentralDirectory();
    checkExpectedBytes(new byte[] {0x50, 0x4b, 0x05, 0x06}, (int) calculatedEndOfCentralDirectory);
    assertThat(centralDirectoryMetadata.getNumEntriesInCentralDirectory())
        .isEqualTo(UnitTestZipArchive.allEntriesInFileOrder.size());
  }

  @Test
  public void testParseCentralDirectoryEntry() throws Exception {
    ByteArrayInputStream in = new ByteArrayInputStream(unitTestZipArchive);
    in.mark(unitTestZipArchive.length);
    int eocdOffset = MinimalZipParser.locateStartOfEocd(unitTestZipArchive);
    assertThat(in.skip(eocdOffset)).isEqualTo(eocdOffset);
    MinimalCentralDirectoryMetadata metadata = MinimalZipParser.parseEocd(in);
    in.reset();
    assertThat(in.skip(metadata.getOffsetOfCentralDirectory()))
        .isEqualTo(metadata.getOffsetOfCentralDirectory());

    // Read each entry and verify all fields *except* the value returned by
    // MinimalZipEntry.getFileOffsetOfCompressedData(), as that has yet to be computed.
    for (UnitTestZipEntry expectedEntry : UnitTestZipArchive.allEntriesInFileOrder) {
      MinimalZipEntry parsed = MinimalZipParser.parseCentralDirectoryEntry(in);
      assertThat(parsed.getFileName()).isEqualTo(expectedEntry.path);

      // Verify that the local signature header is at the calculated position
      byte[] expectedSignatureBlock = new byte[] {0x50, 0x4b, 0x03, 0x04};
      for (int index = 0; index < 4; index++) {
        byte actualByte = unitTestZipArchive[((int) parsed.getFileOffsetOfLocalEntry()) + index];
        assertThat(actualByte).isEqualTo(expectedSignatureBlock[index]);
      }

      if (expectedEntry.level > 0) {
        assertThat(parsed.getCompressionMethod()).isEqualTo(8 /* deflate */);
      } else {
        assertThat(parsed.getCompressionMethod()).isEqualTo(0 /* store */);
      }
      byte[] uncompressedContent = expectedEntry.getUncompressedBinaryContent();
      assertThat(parsed.getUncompressedSize()).isEqualTo(uncompressedContent.length);
      CRC32 crc32 = new CRC32();
      crc32.update(uncompressedContent);
      assertThat(parsed.getCrc32OfUncompressedData()).isEqualTo(crc32.getValue());
      byte[] compressedContent = expectedEntry.getCompressedBinaryContent();
      assertThat(parsed.getCompressedSize()).isEqualTo(compressedContent.length);
    }
  }

  @Test
  public void testParseLocalEntryAndGetCompressedDataOffset() throws Exception {
    ByteArrayInputStream in = new ByteArrayInputStream(unitTestZipArchive);
    in.mark(unitTestZipArchive.length);
    int eocdOffset = MinimalZipParser.locateStartOfEocd(unitTestZipArchive);
    assertThat(in.skip(eocdOffset)).isEqualTo(eocdOffset);
    MinimalCentralDirectoryMetadata metadata = MinimalZipParser.parseEocd(in);
    in.reset();
    assertThat(in.skip(metadata.getOffsetOfCentralDirectory()))
        .isEqualTo(metadata.getOffsetOfCentralDirectory());

    // Read each entry and verify all fields *except* the value returned by
    // MinimalZipEntry.getFileOffsetOfCompressedData(), as that has yet to be computed.
    List<MinimalZipEntry> parsedEntries = new ArrayList<MinimalZipEntry>();
    for (int x = 0; x < UnitTestZipArchive.allEntriesInFileOrder.size(); x++) {
      parsedEntries.add(MinimalZipParser.parseCentralDirectoryEntry(in));
    }

    for (int x = 0; x < UnitTestZipArchive.allEntriesInFileOrder.size(); x++) {
      UnitTestZipEntry expectedEntry = UnitTestZipArchive.allEntriesInFileOrder.get(x);
      MinimalZipEntry parsedEntry = parsedEntries.get(x);
      in.reset();
      assertThat(in.skip(parsedEntry.getFileOffsetOfLocalEntry()))
          .isEqualTo(parsedEntry.getFileOffsetOfLocalEntry());
      long relativeDataOffset = MinimalZipParser.parseLocalEntryAndGetCompressedDataOffset(in);
      assertThat(relativeDataOffset > 0).isTrue();
      checkExpectedBytes(
          expectedEntry.getCompressedBinaryContent(),
          (int) (parsedEntry.getFileOffsetOfLocalEntry() + relativeDataOffset));
    }
  }
}
