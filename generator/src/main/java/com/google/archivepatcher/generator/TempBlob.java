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

import com.google.archivepatcher.shared.bytesource.ByteSource;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;

/**
 * A closeable container for a temp blob that deletes itself on {@link #close()}. This is convenient
 * for try-with-resources constructs that need to use temp files in scope. The blob is moved to disk
 * from memory when it exceeds {@link #maxBytesInMemory} in size.
 */
public class TempBlob implements Closeable {

  private static final long BITS_IN_BYTE = 8;

  private final int maxBytesInMemory;
  /** The file that is wrapped by this blob. */
  private File file;
  private boolean inMemory = true;
  private ByteArrayOutputStream byteArrayOutputStream;
  private BufferedOutputStream bufferedFileOutputStream;
  /** If the OutputStream to this blob is still open. */
  private boolean isWriting = false;
  /** If the blob has been closed for read/write. */
  private boolean isClosed = false;

  /**
   * Create a new in-memory blob which is moved to a file on-disk when blob size exceeds 5MiB. All
   * data files will be destroyed on {@link #close()}
   */
  public TempBlob() {
    this(5 * 1024 * 1024);
  }

  /**
   * Create a new in-memory blob which is moved to a file on-disk when blob size exceeds {@code
   * maxBytesInMemory} bytes. All data files will be destroyed on {@link #close()}
   *
   * @param maxBytesInMemory Size in bytes exceeding which the data is moved from being in-memory to
   *     on-disk.
   */
  public TempBlob(int maxBytesInMemory) {
    this.maxBytesInMemory = maxBytesInMemory;
    byteArrayOutputStream = new ByteArrayOutputStream();
  }

  /** Obtain the content of this blob as {@link ByteSource}. */
  public ByteSource asByteSource() throws IOException {
    throwIOExceptionIfClosed();
    throwIOExceptionIfWriting();
    return inMemory
        ? ByteSource.wrap(byteArrayOutputStream.toByteArray())
        : ByteSource.fromFile(file);
  }

  /** If the blob is stored in memory or on disk. */
  public boolean isInMemory() {
    return inMemory;
  }

  /** Returns a buffered {@link OutputStream} to write to this blob. */
  public OutputStream openBufferedStream() throws IOException {
    throwIOExceptionIfClosed();
    throwIOExceptionIfWriting();
    isWriting = true;
    if (inMemory && byteArrayOutputStream == null) {
      byteArrayOutputStream = new ByteArrayOutputStream();
    }
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        copyToDiskIfRequired(Integer.SIZE / BITS_IN_BYTE);
        getOutputStream().write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        copyToDiskIfRequired(b.length);
        getOutputStream().write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        isWriting = false;
        getOutputStream().close();
      }

      @Override
      public void flush() throws IOException {
        getOutputStream().flush();
      }

      private OutputStream getOutputStream() {
        return inMemory ? byteArrayOutputStream : bufferedFileOutputStream;
      }

      private void copyToDiskIfRequired(long bytesToBeWritten) throws IOException {
        if (inMemory && byteArrayOutputStream.size() + bytesToBeWritten > maxBytesInMemory) {
          createNewFile();
          bufferedFileOutputStream = new BufferedOutputStream(new FileOutputStream(file));
          byteArrayOutputStream.writeTo(bufferedFileOutputStream);
          inMemory = false;
          byteArrayOutputStream = null;
        }
      }
    };
  }

  /** Returns the size of the content written to this blob. */
  public long length() throws IOException {
    throwIOExceptionIfClosed();
    return inMemory ? byteArrayOutputStream.size() : file.length();
  }

  /** Clears the content of this blob. */
  public void clear() throws IOException {
    throwIOExceptionIfClosed();
    throwIOExceptionIfWriting();
    if (byteArrayOutputStream != null) {
      byteArrayOutputStream.reset();
    }
    deleteFile();
    inMemory = true;
  }

  @Override
  public void close() {
    byteArrayOutputStream = null;
    deleteFile();
    isClosed = true;
  }

  @Override
  protected void finalize() {
    // Close TempBlob in case callers forgot to do so.
    // There is a risk that we pile up temp file orphans without this.
    close();
  }

  private void createNewFile() throws IOException {
    file = File.createTempFile("archive_patcher", "tmp");
    file.deleteOnExit();
  }

  private void deleteFile() {
    if (file != null) {
      file.delete();
      file = null;
    }
  }

  private void throwIOExceptionIfWriting() throws IOException {
    if (isWriting) {
      throw new IOException("A previous stream is still open for writing.");
    }
  }

  private void throwIOExceptionIfClosed() throws IOException {
    if (isClosed) {
      throw new IOException("The blob is has been closed.");
    }
  }
}
