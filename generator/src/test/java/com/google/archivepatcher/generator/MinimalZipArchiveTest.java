// Copyright 2016 Google Inc. All rights reserved.
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MinimalZipParser}. */
@RunWith(JUnit4.class)
@SuppressWarnings("javadoc")
public class MinimalZipArchiveTest {
  private byte[] unitTestZipArchive;
  private File tempFile;

  @Before
  public void setup() throws Exception {
    unitTestZipArchive = UnitTestZipArchive.makeTestZip();
    tempFile = File.createTempFile("MinimalZipArchiveTest", "zip");
    tempFile.deleteOnExit();
    try {
      FileOutputStream out = new FileOutputStream(tempFile);
      out.write(unitTestZipArchive);
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
  }

  @After
  public void tearDown() {
    if (tempFile != null) {
      try {
        tempFile.delete();
      } catch (Exception ignored) {
        // Nothing
      }
    }
  }

  @Test
  public void testListEntries() throws IOException {
    // Ensure all entries are found, and that they are in file order.
    List<MinimalZipEntry> parsedEntries = MinimalZipArchive.listEntries(tempFile);
    long lastSeenHeaderOffset = -1;
    for (int x = 0; x < UnitTestZipArchive.allEntriesInFileOrder.size(); x++) {
      UnitTestZipEntry expected = UnitTestZipArchive.allEntriesInFileOrder.get(x);
      MinimalZipEntry actual = parsedEntries.get(x);
      assertThat(actual.getFileName()).isEqualTo(expected.path);
      assertThat(actual.getCompressionMethod()).isEqualTo(expected.level == 0 ? 0 : 8);
      assertThat(actual.getCompressedSize())
          .isEqualTo(expected.getCompressedBinaryContent().length);
      assertThat(actual.getUncompressedSize())
          .isEqualTo(expected.getUncompressedBinaryContent().length);
      assertThat(actual.getGeneralPurposeFlagBit11()).isFalse();
      CRC32 crc32 = new CRC32();
      crc32.update(expected.getUncompressedBinaryContent());
      assertThat(actual.getCrc32OfUncompressedData()).isEqualTo(crc32.getValue());

      // Offset verification is a little trickier
      // 1. Verify that the offsets are in ascending order and increasing.
      assertThat(actual.getFileOffsetOfLocalEntry() > lastSeenHeaderOffset).isTrue();
      lastSeenHeaderOffset = actual.getFileOffsetOfLocalEntry();

      // 2. Verify that the local signature header is at the calculated position
      byte[] expectedSignatureBlock = new byte[] {0x50, 0x4b, 0x03, 0x04};
      for (int index = 0; index < 4; index++) {
        byte actualByte = unitTestZipArchive[((int) actual.getFileOffsetOfLocalEntry()) + index];
        assertThat(actualByte).isEqualTo(expectedSignatureBlock[index]);
      }

      // 3. Verify that the data is at the calculated position
      byte[] expectedContent = expected.getCompressedBinaryContent();
      int calculatedDataOffset = (int) actual.getFileOffsetOfCompressedData();
      for (int index = 0; index < expectedContent.length; index++) {
        assertThat(unitTestZipArchive[calculatedDataOffset + index])
            .isEqualTo(expectedContent[index]);
      }
    }
  }
}
