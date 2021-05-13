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

import static com.google.archivepatcher.shared.TestUtils.assertThrows;
import static com.google.common.truth.Truth.assertThat;

import com.google.archivepatcher.shared.bytesource.ByteSource;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TempBlob}. */
@RunWith(JUnit4.class)
@SuppressWarnings("javadoc")
public class TempBlobTest {

  @Test
  public void testLength() throws Exception {
    byte[] expected = new byte[] {2, 4, 0, 8};
    TempBlob tempBlob = new TempBlob();
    writeToBlob(expected, tempBlob);

    assertThat(tempBlob.length()).isEqualTo(expected.length);
  }

  @Test
  public void testWriteAndRead() throws Exception {
    byte[] expected = new byte[] {2, 4, 0, 8};
    TempBlob tempBlob = new TempBlob();
    writeToBlob(expected, tempBlob);

    assertThat(toByteArray(tempBlob)).isEqualTo(expected);
  }

  @Test
  public void testClearBlob() throws IOException {
    byte[] oldData = new byte[] {1, 2, 3, 4};
    byte[] newData = new byte[] {5, 6, 7, 8};
    TempBlob tempBlob = new TempBlob();
    writeToBlob(oldData, tempBlob);
    tempBlob.clear();
    writeToBlob(newData, tempBlob);

    assertThat(toByteArray(tempBlob)).isEqualTo(newData);
  }

  @Test
  public void testOpenStreamWithoutClosing() throws Exception {
    TempBlob tempBlob = new TempBlob();
    tempBlob.openBufferedStream();

    assertThrows(IOException.class, tempBlob::openBufferedStream);
  }

  @Test
  public void testClearWithoutClosing() throws Exception {
    TempBlob tempBlob = new TempBlob();
    tempBlob.openBufferedStream();

    assertThrows(IOException.class, tempBlob::clear);
  }

  @Test
  public void testAsByteSourceWithoutClosing() throws Exception {
    TempBlob tempBlob = new TempBlob();
    tempBlob.openBufferedStream();

    assertThrows(IOException.class, tempBlob::asByteSource);
  }

  @Test
  public void testFinalizingClosesTempBlob() throws Exception {
    TempBlob tempBlob = new TempBlob();
    tempBlob.finalize();

    assertThrows(IOException.class, tempBlob::asByteSource);
  }

  @Test
  public void testWriteToMemory() throws Exception {
    byte[] expected = new byte[] {2, 4, 0, 8};
    TempBlob tempBlob = new TempBlob();
    writeToBlob(expected, tempBlob);

    assertThat(tempBlob.isInMemory()).isTrue();
    assertThat(toByteArray(tempBlob)).isEqualTo(expected);
  }

  @Test
  public void testWriteToDisk() throws Exception {
    int maxBytesInMemory = 100;
    TempBlob tempBlob = new TempBlob(100);
    byte[] data = new byte[maxBytesInMemory + 1];
    new Random().nextBytes(data);
    writeToBlob(data, tempBlob);

    assertThat(tempBlob.isInMemory()).isFalse();
    assertThat(toByteArray(tempBlob)).isEqualTo(data);
  }

  @Test
  public void testSequentialWrites() throws Exception {
    int maxBytesInMemory = 100;
    TempBlob tempBlob = new TempBlob(100);
    byte[] data = new byte[maxBytesInMemory + 1];
    new Random().nextBytes(data);
    writeToBlob(data, tempBlob);
    writeToBlob(data, tempBlob);
  }

  @Test
  public void testResetToInMemoryOnClear() throws Exception {
    int maxBytesInMemory = 100;
    TempBlob tempBlob = new TempBlob(maxBytesInMemory);
    byte[] data = new byte[maxBytesInMemory + 1];
    new Random().nextBytes(data);
    writeToBlob(data, tempBlob);

    // Verify that we switched from inMemory to onDisk.
    assertThat(tempBlob.isInMemory()).isFalse();

    // Clear and write again with size < TempBlob.maxBytesInMemory.
    byte[] expected = new byte[] {2, 4, 0, 8};
    tempBlob.clear();
    writeToBlob(expected, tempBlob);

    // Verify that we are back from onDisk to inMemory.
    assertThat(tempBlob.isInMemory()).isTrue();
    assertThat(toByteArray(tempBlob)).isEqualTo(expected);
  }

  private static void writeToBlob(byte[] data, TempBlob tempBlob) throws IOException {
    try (OutputStream outputStream = tempBlob.openBufferedStream()) {
      outputStream.write(data);
    }
  }

  private static byte[] toByteArray(TempBlob tempBlob) throws IOException {
    try (ByteSource source = tempBlob.asByteSource();
        InputStream is = source.openStream()) {
      return ByteStreams.toByteArray(is);
    }
  }
}
