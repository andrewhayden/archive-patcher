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

package com.google.archivepatcher.shared;

import static com.google.common.truth.Truth.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link TypedRange}.
 */
@RunWith(JUnit4.class)
@SuppressWarnings("javadoc")
public class TypedRangeTest {

  @Test
  public void testGetters() {
    String text = "hello";
    TypedRange<String> range = new TypedRange<String>(555, 777, text);
    assertThat(range.getOffset()).isEqualTo(555);
    assertThat(range.getLength()).isEqualTo(777);
    assertThat(text).isSameAs(range.getMetadata());
  }

  @Test
  public void testToString() {
    // Just make sure this doesn't crash.
    TypedRange<String> range = new TypedRange<String>(555, 777, "woohoo");
    assertThat(range.toString()).isNotNull();
    assertThat(range.toString()).isNotEmpty();
  }

  @Test
  @SuppressWarnings("SelfComparison") // self comparison is intentional here for testing compareTo.
  public void testCompare() {
    TypedRange<String> range1 = new TypedRange<String>(1, 777, null);
    TypedRange<String> range2 = new TypedRange<String>(2, 777, null);
    assertThat(range1).isLessThan(range2);
    assertThat(range2).isGreaterThan(range1);
    assertThat(range1).isEquivalentAccordingToCompareTo(range1);
  }

  @Test
  public void testHashCode() {
    TypedRange<String> range1a = new TypedRange<String>(123, 456, "hi mom");
    TypedRange<String> range1b = new TypedRange<String>(123, 456, "hi mom");
    assertThat(range1b.hashCode()).isEqualTo(range1a.hashCode());
    Set<Integer> hashCodes = new HashSet<Integer>();
    hashCodes.add(range1a.hashCode());
    hashCodes.add(new TypedRange<String>(123 + 1, 456, "hi mom").hashCode()); // offset changed
    hashCodes.add(new TypedRange<String>(123, 456 + 1, "hi mom").hashCode()); // length changed
    hashCodes.add(new TypedRange<String>(123 + 1, 456, "x").hashCode()); // metadata changed
    hashCodes.add(new TypedRange<String>(123 + 1, 456, null).hashCode()); // no metadata at all
    // Assert that all 4 hash codes are unique
    assertThat(hashCodes).hasSize(5);
  }

  @Test
  @SuppressWarnings("TruthSelfEquals") // we are testing equals here.
  public void testEquals() {
    TypedRange<String> range1a = new TypedRange<String>(123, 456, "hi mom");
    assertThat(range1a).isEqualTo(range1a); // identity case
    TypedRange<String> range1b = new TypedRange<String>(123, 456, "hi mom");
    assertThat(range1b).isEqualTo(range1a); // equality case
    assertThat(range1a).isNotEqualTo(new TypedRange<String>(123 + 1, 456, "hi mom")); // offset
    assertThat(range1a).isNotEqualTo(new TypedRange<String>(123, 456 + 1, "hi mom")); // length
    assertThat(range1a).isNotEqualTo(new TypedRange<String>(123, 456, "foo")); // metadata
    assertThat(range1a).isNotEqualTo(new TypedRange<String>(123, 456, null)); // no metadata
    assertThat(new TypedRange<String>(123, 456, null)).isNotEqualTo(range1a); // other code branch
    assertThat(new TypedRange<String>(123, 456, null))
        .isEqualTo(new TypedRange<String>(123, 456, null)); // both with null metadata
    assertThat(range1a).isNotEqualTo(null); // versus null
    assertThat(range1a).isNotEqualTo("space channel 5"); // versus object of different class
  }
}
