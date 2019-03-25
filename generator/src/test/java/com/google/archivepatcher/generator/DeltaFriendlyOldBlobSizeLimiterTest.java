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

import static com.google.archivepatcher.generator.PreDiffPlanEntryTestUtils.getEntryBuilderWithBothEntriesUncompressed;
import static com.google.archivepatcher.generator.PreDiffPlanEntryTestUtils.getEntryBuilderWithCompressedBytesChanged;
import static com.google.archivepatcher.generator.PreDiffPlanEntryTestUtils.getEntryBuilderWithCompressedBytesIdentical;
import static com.google.archivepatcher.generator.PreDiffPlanEntryTestUtils.getEntryBuilderWithCompressedToUncompressed;
import static com.google.archivepatcher.generator.PreDiffPlanEntryTestUtils.getEntryBuilderWithUnsuitable;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DeltaFriendlyOldBlobSizeLimiter}. */
@RunWith(JUnit4.class)
@SuppressWarnings("javadoc")
public class DeltaFriendlyOldBlobSizeLimiterTest {
  private static final int DEFLATE_COMPRESSION_METHOD = 8;

  private static final MinimalZipEntry UNIMPORTANT = makeFakeEntry("/unimportant", 1337, 1337);
  private static final MinimalZipEntry ENTRY_A_100K =
      makeFakeEntry("/a/100k", 100 * 1024, 200 * 1024);
  private static final MinimalZipEntry ENTRY_B_200K =
      makeFakeEntry("/b/200k", 100 * 1024, 300 * 1024);
  private static final MinimalZipEntry ENTRY_C_300K =
      makeFakeEntry("/c/300k", 100 * 1024, 400 * 1024);
  private static final MinimalZipEntry ENTRY_D_400K =
      makeFakeEntry("/d/400k", 100 * 1024, 500 * 1024);
  private static final MinimalZipEntry IGNORED_A = makeFakeEntry("/ignored/a", 1234, 5678);
  private static final MinimalZipEntry IGNORED_B = makeFakeEntry("/ignored/b", 5678, 9101112);
  private static final MinimalZipEntry IGNORED_C = makeFakeEntry("/ignored/c", 9101112, 13141516);

  // First four entries are all ones where uncompression of the old resource is required.
  // Note that there is a mix of UNCOMPRESS_OLD and UNCOMPRESS_BOTH, both of which will have the
  // "old" entry flagged for uncompression (i.e., should be relevant to the filtering logic).
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_A_100K =
      getEntryBuilderWithCompressedBytesChanged().setZipEntries(ENTRY_A_100K, UNIMPORTANT).build();
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_B_200K =
      getEntryBuilderWithCompressedToUncompressed()
          .setZipEntries(ENTRY_B_200K, UNIMPORTANT)
          .build();
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_C_300K =
      getEntryBuilderWithCompressedBytesChanged().setZipEntries(ENTRY_C_300K, UNIMPORTANT).build();
  // Here we deliberately use UNCOMPRESS_BOTH to test that it has the same effect as UNCOMPRESS_OLD.
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_D_400K =
      PreDiffPlanEntry.builder()
          .setZipEntries(ENTRY_D_400K, UNIMPORTANT)
          .setUncompressionOption(
              ZipEntryUncompressionOption.UNCOMPRESS_BOTH,
              UncompressionOptionExplanation.COMPRESSED_CHANGED_TO_UNCOMPRESSED)
          .build();

  // Remaining entries are all ones where recompression is NOT required. Note the mixture of
  // UNCOMPRESS_NEITHER and UNCOMPRESS_OLD, neither of which will have the "new" entry flagged for
  // recompression (ie., must be ignored by the filtering logic).
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_IGNORED_A_UNCHANGED =
      getEntryBuilderWithCompressedBytesIdentical().setZipEntries(IGNORED_A, UNIMPORTANT).build();
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_IGNORED_B_BOTH_UNCOMPRESSED =
      getEntryBuilderWithBothEntriesUncompressed().setZipEntries(IGNORED_B, UNIMPORTANT).build();
  private static final PreDiffPlanEntry PRE_DIFF_PLAN_ENTRY_IGNORED_C_UNSUITABLE =
      getEntryBuilderWithUnsuitable().setZipEntries(IGNORED_C, UNIMPORTANT).build();

  /** Convenience reference to all the entries that should be ignored by filtering. */
  private static final List<PreDiffPlanEntry> ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES =
      Collections.unmodifiableList(
          Arrays.asList(
              PRE_DIFF_PLAN_ENTRY_IGNORED_A_UNCHANGED,
              PRE_DIFF_PLAN_ENTRY_IGNORED_B_BOTH_UNCOMPRESSED,
              PRE_DIFF_PLAN_ENTRY_IGNORED_C_UNSUITABLE));

  /** Convenience reference to all the entries that are subject to filtering. */
  private static final List<PreDiffPlanEntry> ALL_PRE_DIFF_PLAN_ENTRIES =
      Collections.unmodifiableList(
          Arrays.asList(
              PRE_DIFF_PLAN_ENTRY_IGNORED_A_UNCHANGED,
              PRE_DIFF_PLAN_ENTRY_A_100K,
              PRE_DIFF_PLAN_ENTRY_IGNORED_B_BOTH_UNCOMPRESSED,
              PRE_DIFF_PLAN_ENTRY_D_400K,
              PRE_DIFF_PLAN_ENTRY_IGNORED_C_UNSUITABLE,
              PRE_DIFF_PLAN_ENTRY_B_200K,
              PRE_DIFF_PLAN_ENTRY_C_300K));

  /**
   * Make a structurally valid but totally bogus {@link MinimalZipEntry} for the purpose of testing
   * the {@link PreDiffPlanEntryModifier}.
   *
   * @param path the path to set on the entry, to help with debugging
   * @param compressedSize the compressed size of the entry, in bytes
   * @param uncompressedSize the uncompressed size of the entry, in bytes
   */
  private static MinimalZipEntry makeFakeEntry(
      String path, long compressedSize, long uncompressedSize) {
    try {
      return new MinimalZipEntry(
          DEFLATE_COMPRESSION_METHOD, // == deflate
          0, // crc32OfUncompressedData (ignored for this test)
          compressedSize,
          uncompressedSize,
          path.getBytes("UTF8"),
          true, // generalPurposeFlagBit11 (true=UTF8)
          0 // fileOffsetOfLocalEntry (ignored for this test)
          );
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e); // Impossible on any modern system
    }
  }

  @Test
  public void testNegativeLimit() {
    try {
      new DeltaFriendlyOldBlobSizeLimiter(-1);
      Assert.fail("Set a negative limit");
    } catch (IllegalArgumentException expected) {
      // Pass
    }
  }

  /**
   * Asserts that the two collections contain exactly the same elements. This isn't as rigorous as
   * it should be, but is ok for this test scenario. Checks the contents but not the iteration order
   * of the collections handed in.
   */
  private static <T> void assertEquivalence(Collection<T> c1, Collection<T> c2) {
    String errorMessage = "Expected " + c1 + " but was " + c2;
    Assert.assertEquals(errorMessage, c1.size(), c2.size());
    Assert.assertTrue(errorMessage, c1.containsAll(c2));
    Assert.assertTrue(errorMessage, c2.containsAll(c1));
  }

  /**
   * Given {@link PreDiffPlanEntry}s, manufacture equivalents altered in the way that the {@link
   * DeltaFriendlyOldBlobSizeLimiter} would.
   *
   * @param originals the original entries
   * @return the altered entries
   */
  private static final List<PreDiffPlanEntry> suppressed(PreDiffPlanEntry... originals) {
    List<PreDiffPlanEntry> result = new ArrayList<>(originals.length);
    for (PreDiffPlanEntry original : originals) {
      result.add(
          original.toBuilder()
              .setUncompressionOption(
                  ZipEntryUncompressionOption.UNCOMPRESS_NEITHER,
                  UncompressionOptionExplanation.RESOURCE_CONSTRAINED)
              .build());
    }
    return result;
  }

  private File tempFile = null;

  @Before
  public void setup() throws IOException {
    // Make an empty file to test the recommender's limitation logic
    tempFile = File.createTempFile("DeltaFriendlyOldBlobSizeLimiterTest", "test");
    tempFile.deleteOnExit();
  }

  @After
  public void tearDown() {
    tempFile.delete();
  }

  @Test
  public void testZeroLimit() {
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(0);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_A_100K,
            PRE_DIFF_PLAN_ENTRY_B_200K,
            PRE_DIFF_PLAN_ENTRY_C_300K,
            PRE_DIFF_PLAN_ENTRY_D_400K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testMaxLimit() {
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(Long.MAX_VALUE);
    assertEquivalence(
        ALL_PRE_DIFF_PLAN_ENTRIES,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_ExactlySmallest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getCompressedSize(); // Exactly large enough
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_A_100K);
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_B_200K, PRE_DIFF_PLAN_ENTRY_C_300K, PRE_DIFF_PLAN_ENTRY_D_400K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_EdgeUnderSmallest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getCompressedSize()
            - 1; // 1 byte too small
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_A_100K,
            PRE_DIFF_PLAN_ENTRY_B_200K,
            PRE_DIFF_PLAN_ENTRY_C_300K,
            PRE_DIFF_PLAN_ENTRY_D_400K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_EdgeOverSmallest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_A_100K.getOldEntry().getCompressedSize()
            + 1; // 1 byte extra room
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_A_100K);
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_B_200K, PRE_DIFF_PLAN_ENTRY_C_300K, PRE_DIFF_PLAN_ENTRY_D_400K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_ExactlyLargest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getCompressedSize(); // Exactly large enough
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_D_400K);
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_A_100K, PRE_DIFF_PLAN_ENTRY_B_200K, PRE_DIFF_PLAN_ENTRY_C_300K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_EdgeUnderLargest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getCompressedSize()
            - 1; // 1 byte too small
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_C_300K);
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_A_100K, PRE_DIFF_PLAN_ENTRY_B_200K, PRE_DIFF_PLAN_ENTRY_D_400K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_EdgeOverLargest() {
    long limit =
        PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getUncompressedSize()
            - PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getCompressedSize()
            + 1; // 1 byte extra room
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_D_400K);
    expected.addAll(
        suppressed(
            PRE_DIFF_PLAN_ENTRY_A_100K, PRE_DIFF_PLAN_ENTRY_B_200K, PRE_DIFF_PLAN_ENTRY_C_300K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }

  @Test
  public void testLimit_Complex() {
    // A more nuanced test. Here we set up a limit of 600k - big enough to get the largest and the
    // THIRD largest files. The second largest will fail because there isn't enough space after
    // adding the first largest, and the fourth largest will fail because there is not enough space
    // after adding the third largest. Tricky.
    long limit =
        (PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getUncompressedSize()
                - PRE_DIFF_PLAN_ENTRY_D_400K.getOldEntry().getCompressedSize())
            + (PRE_DIFF_PLAN_ENTRY_B_200K.getOldEntry().getUncompressedSize()
                - PRE_DIFF_PLAN_ENTRY_B_200K.getOldEntry().getCompressedSize());
    DeltaFriendlyOldBlobSizeLimiter limiter = new DeltaFriendlyOldBlobSizeLimiter(limit);
    List<PreDiffPlanEntry> expected = new ArrayList<>();
    expected.add(PRE_DIFF_PLAN_ENTRY_B_200K);
    expected.add(PRE_DIFF_PLAN_ENTRY_D_400K);
    expected.addAll(suppressed(PRE_DIFF_PLAN_ENTRY_A_100K, PRE_DIFF_PLAN_ENTRY_C_300K));
    expected.addAll(ALL_IGNORED_PRE_DIFF_PLAN_ENTRIES);
    assertEquivalence(
        expected,
        limiter.getModifiedPreDiffPlanEntries(tempFile, tempFile, ALL_PRE_DIFF_PLAN_ENTRIES));
  }
}
