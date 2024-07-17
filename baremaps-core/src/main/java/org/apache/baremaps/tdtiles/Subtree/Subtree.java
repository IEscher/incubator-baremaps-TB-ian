/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.baremaps.tdtiles.Subtree;


import java.util.BitSet;

public class Subtree {

  private final Availability tileAvailability;
  private final Availability contentAvailability;
  private final Availability childSubtreeAvailability;
  private final int levels;

  public Subtree(Availability tileAvailability,
                 Availability contentAvailability,
                 Availability childSubtreeAvailability,
                 int levels) {
    this.tileAvailability = tileAvailability;
    this.contentAvailability = contentAvailability;
    this.childSubtreeAvailability = childSubtreeAvailability;
    this.levels = levels;
    if (levels != tileAvailability.getAvailabilities().length
        || levels != contentAvailability.getAvailabilities().length) {
      throw new IllegalArgumentException("The number of levels (" + levels +
          ") must be equal to the number of availabilities (tile: "
          + tileAvailability.getAvailabilities().length +
          ", content: " + contentAvailability.getAvailabilities().length + ").");
    }
  }

  public Availability getTileAvailability() {
    return tileAvailability;
  }

  public BitSet getTileBitSet() {
    return tileAvailability.getBitSet(false);
  }

  public Availability getContentAvailability() {
    return contentAvailability;
  }

  public BitSet getContentBitSet() {
    return contentAvailability.getBitSet(false);
  }

  public Availability getChildSubtreeAvailability() {
    return childSubtreeAvailability;
  }

  public BitSet getChildSubtreeBitSet() {
    return childSubtreeAvailability.getBitSet(true);
  }

  public BitSet getEquivalentChildSubtreeBitSet() {
    if (levels < 2) {
      throw new RuntimeException("Subtree is too small to have a child subtree.");
    }
    BitSet simplifiedBitSet = new BitSet(4);
    for (int i = 0; i < 4; i++) {
      simplifiedBitSet.set(i, tileAvailability.getAvailability(1).get(i));
    }
    return simplifiedBitSet;
  }

  public int getLevels() {
    return levels;
  }

  public static Subtree concatenateSubtreeLevel(Subtree[] subtrees) {
    if (subtrees.length != 4) {
      throw new IllegalArgumentException("The subtrees array must have exactly 4 elements.");
    }
    for (int i = 1; i < subtrees.length; i++) {
      if (subtrees[i].getLevels() != subtrees[0].getLevels()) {
        throw new IllegalArgumentException("The subtrees must have the same number of levels.");
      }
    }
    for (int i = 0; i < subtrees.length; i++) {
      if (subtrees[i].getLevels() < 1) {
        throw new IllegalArgumentException("The subtrees must have at least one level.");
      }
    }
    for (int i = 1; i < subtrees.length; i++) {
      if (subtrees[0].levels != subtrees[i].levels) {
        throw new IllegalArgumentException("The subtrees must have the same number of levels.");
      }
    }

    Availability[] tileAvailabilities = new Availability[4];
    Availability[] contentAvailabilities = new Availability[4];
    Availability[] childSubtreeAvailabilities = new Availability[4];
    for (int i = 0; i < 4; i++) {
      tileAvailabilities[i] = subtrees[i].getTileAvailability();
      contentAvailabilities[i] = subtrees[i].getContentAvailability();
      childSubtreeAvailabilities[i] = subtrees[i].getChildSubtreeAvailability();
    }

    Availability parentTileAvailability =
        Availability.concatenateAvailabilities(tileAvailabilities, false, false);
    Availability parentContentAvailability =
        Availability.concatenateAvailabilities(contentAvailabilities, false, true);
    Availability parentChildSubtreeAvailability =
        Availability.concatenateAvailabilities(childSubtreeAvailabilities, true, false);
    int contentCount = parentContentAvailability.getBitSet(false).cardinality();

//    if (parentChildSubtreeAvailability.getBitSet(true).isEmpty()) {
//      System.err.println("Empty child subtree availability: "
//          + parentChildSubtreeAvailability.getBitSet(true).toString());
//    }

    return new Subtree(parentTileAvailability, parentContentAvailability,
        parentChildSubtreeAvailability,
        subtrees[0].getLevels() + 1);
  }

  public static Subtree getSimplifiedSubtree(Subtree subtree) {

    BitSet tileBitSet = new BitSet(1);
    tileBitSet.set(0, subtree.getTileAvailability().isAvailable());
    BitSet contentBitSet = new BitSet(1);
    contentBitSet.set(0, subtree.getContentAvailability().isAvailable());
    BitSet childSubtreeBitSet = subtree.getEquivalentChildSubtreeBitSet();

    return new Subtree(
        new Availability(tileBitSet, 1, false),
        new Availability(contentBitSet, 1, false),
        new Availability(childSubtreeBitSet, 4, true),
        1);
  }

  public void displayTileAvailability() {
    for (int i = 0; i < tileAvailability.getAvailabilities().length; i++) {
      System.out.println("Level " + i + ": " + tileAvailability.getAvailability(i).toString());
    }
    System.out.println("All levels: " + getTileBitSet().toString());
  }

  public void displayChildAvailability() {
    System.out.println("Children availability: " + getChildSubtreeBitSet().toString());
  }
}
