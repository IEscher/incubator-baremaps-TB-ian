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

public class Availability {

  private final BitSet[] availabilities;
  private final int length;
  private final int levels;
  private final boolean isChildren;

  public Availability(BitSet[] availabilities, int totalLength, boolean isChildren) {
    if (isChildren && availabilities.length != 1) {
      throw new IllegalArgumentException("Children availability must have only one level.");
    }
    this.availabilities = availabilities;
    this.length = totalLength;
    this.levels = availabilities.length;
    this.isChildren = isChildren;
  }

  public Availability(BitSet availability, int totalLength, boolean isChildren) {
    this.isChildren = isChildren;
    if (availability.length() - 1 > totalLength) {
      throw new IllegalArgumentException("Availability's length (" + availability.length() +
          ") must be less than or equal to the total length (" + totalLength + ").");
    }
    this.length = totalLength;

    if (!isChildren) {
      double levelsDouble = Math.log(totalLength * 3 + 1) / Math.log(4);
      if (levelsDouble % 1 != 0) {
        throw new IllegalArgumentException("Unbalanced availability bitset.");
      }
      int levels = (int) levelsDouble;
      availabilities = new BitSet[levels];

      availabilities[0] = availability.get(0, 1);
      for (int i = 1; i < levels; i++) {
        int fromIndex = ((int) Math.pow(4, i) - 1) / 3;
        int toIndex = ((int) Math.pow(4, i + 1) - 1) / 3;
        availabilities[i] = availability.get(fromIndex, toIndex);
      }

      this.levels = levels;
    } else {
      double verification = (Math.log(totalLength) / Math.log(4));
      if (verification % 1 != 0) {
        throw new IllegalArgumentException("Availability's length must be a power of 4.");
      }
      this.levels = 1;
      availabilities = new BitSet[1];
      availabilities[0] = availability;
    }
  }

  public BitSet[] getAvailabilities() {
    return availabilities;
  }

  public BitSet getAvailability(int level) {
    return availabilities[level];
  }

  public BitSet getBitSet(boolean isChildren) {
    if (isChildren) {
      return availabilities[0];
    }
    BitSet bitSet = new BitSet(length);
    int pos = 0;
    for (int i = 0; i < levels; i++) {
      int length = (int) Math.pow(4, i);
      for (int j = 0; j < length; j++) {
        bitSet.set(pos, availabilities[i].get(j));
        pos++;
      }
    }
    return bitSet;
  }

  public int getLevels() {
    return levels;
  }

  public int getLength() {
    return length;
  }

  public boolean isChildren() {
    return isChildren;
  }

  public boolean isAvailable() {
    return availabilities[0].get(0);
  }

  public static Availability generateTileAvailability(BitSet availability, int length) {
    if (availability.length() - 1 > length) {
      throw new IllegalArgumentException("Availability's length (" + availability.length() +
          ") must be less than or equal to the total length (" + length + ").");
    }
    double levelsDouble = Math.log(length) / Math.log(4) + 1;
    if (levelsDouble % 1 != 0) {
      throw new IllegalArgumentException("Length must be a power of 4.");
    }
    int levels = (int) levelsDouble;
    int totalLength = ((int) Math.pow(4, levels) - 1) / 3;
    BitSet[] availabilities = new BitSet[levels];

    availabilities[levels - 1] = availability;
    for (int i = levels - 2; i >= 0; i--) {
      availabilities[i] = new BitSet((int) Math.pow(4, i));
      for (int j = 0; j < (int) Math.pow(4, i + 1); j += 4) {
        if (!availabilities[i + 1].get(j, j + 4).isEmpty()) {
          availabilities[i].set(j / 4);
        }
      }
    }

    return new Availability(availabilities, totalLength, false);
  }

  public static Availability generateContentAvailability(BitSet availability, int length,
      int minLevel) {
    if (availability.length() > length) {
      throw new IllegalArgumentException(
          "Availability's length must be less than or equal to the total length.");
    }
    double levelsDouble = Math.log(length) / Math.log(4) + 1;
    if (levelsDouble % 1 != 0) {
      throw new IllegalArgumentException("Length must be a power of 4.");
    }
    int levels = (int) levelsDouble;
    int totalLength = ((int) Math.pow(4, levels) - 1) / 3;
    BitSet[] availabilities = new BitSet[levels];

    availabilities[levels - 1] = availability;
    for (int i = levels - 2; i >= 0; i--) {
      availabilities[i] = new BitSet((int) Math.pow(4, i));

      for (int j = 0; j < (int) Math.pow(4, i + 1); j += 4) {
        if (!availabilities[i + 1].get(j, j + 4).isEmpty() && i >= (minLevel - 1)) {
          availabilities[i].set(j / 4);
        }
      }

    }

    // for (int i = 0; i < levels; i++) {
    // availabilities[i] = new BitSet((int) Math.pow(4, i));
    // }
    // if (!availability.isEmpty()) {
    // availabilities[0].set(0);
    // }

    return new Availability(availabilities, totalLength, false);
  }

  public static Availability concatenateAvailabilities(Availability[] availabilities,
      boolean isChildren, boolean isContent) {
    if (availabilities.length != 4) {
      throw new IllegalArgumentException(
          "The availabilities array must have exactly 4 Availability elements.");
    }
    int levels = availabilities[0].getLevels();
    for (int i = 1; i < availabilities.length; i++) {
      if (availabilities[i].getLevels() != levels) {
        throw new IllegalArgumentException(
            "The availabilities must have the same number of levels.");
      }
    }

    BitSet[] concatenatedAvailabilities;
    if (isChildren) {
      // Directly concatenate the availabilities
      concatenatedAvailabilities = new BitSet[1];
      concatenatedAvailabilities[0] = new BitSet();
      for (int i = 0; i < 4; i++) {
        for (int j = 0; j < availabilities[i].getLength(); j++) {
          if (availabilities[i].getAvailability(0).get(j)) {
            concatenatedAvailabilities[0].set(i * availabilities[i].getLength() + j);
          }
        }
      }
    } else {
      // Concatenate each level separately
      concatenatedAvailabilities = new BitSet[levels + 1];
      for (int i = 0; i < levels; i++) {
        int length = (int) Math.pow(4, i);
        concatenatedAvailabilities[i + 1] = new BitSet(length * 4);
        for (int j = 0; j < 4; j++) {
          for (int k = 0; k < length; k++) {
            concatenatedAvailabilities[i + 1].set(j * length + k,
                availabilities[j].getAvailability(i).get(k));
          }
        }
      }
    }

    // Set the availability of the root node
    if (!isChildren) {
      concatenatedAvailabilities[0] = new BitSet(1);
      if (!isContent) {
        concatenatedAvailabilities[0].set(0, !concatenatedAvailabilities[1].isEmpty());
      }
    }

    int totalLength;
    if (isChildren) {
      totalLength = 4 * availabilities[0].getLength();
    } else {
      totalLength = ((int) Math.pow(4, levels + 1) - 1) / 3;
    }

    return new Availability(concatenatedAvailabilities, totalLength, isChildren);
  }
}
