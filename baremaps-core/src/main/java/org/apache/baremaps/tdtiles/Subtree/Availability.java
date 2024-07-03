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
    if (availability.length() > totalLength) {
      throw new IllegalArgumentException("Availability's length must be less than or equal to the total length.");
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

  public BitSet getBitSet() {
    BitSet bitSet = new BitSet();
    int pos = 0;
    for (int i = 0; i < availabilities.length; i++) {
      for (int j = 0; j < availabilities[i].length(); j++) {
        if (availabilities[i].get(j)) {
          bitSet.set(pos);
        }
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

  public static Availability generateAvailabilities(BitSet availability, int length, boolean isChildren) {
    if (isChildren) {
      return new Availability(availability, length, true);
    }
    if (availability.length() > length) {
      throw new IllegalArgumentException("Availability's length must be less than or equal to the total length.");
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

  public static Availability concatenateAvailabilities(Availability[] availabilities, boolean isChildren) {
    if (availabilities.length != 4) {
      throw new IllegalArgumentException("The availabilities array must have exactly 4 Availability elements.");
    }
    int levels = availabilities[0].getLevels();
    for (int i = 1; i < availabilities.length; i++) {
      if (availabilities[i].getLevels() != levels) {
        throw new IllegalArgumentException("The availabilities must have the same number of levels.");
      }
    }

    // Concatenate each level separately
    BitSet[] concatenatedAvailabilities = new BitSet[levels + 1];
    for (int i = 0; i < levels; i++) {
      concatenatedAvailabilities[i + 1] = new BitSet();
      int length = (int) Math.pow(4, i);
      for (int j = 0; j < 4; j++) {
        for (int k = 0; k < length; k++) {
          if (availabilities[j].getAvailability(i).get(k)) {
            concatenatedAvailabilities[i + 1].set(j * length + k);
          }
        }
      }
    }

    // Set the availability of the root node
    concatenatedAvailabilities[0] = new BitSet();
    if (!concatenatedAvailabilities[1].isEmpty()) {
      concatenatedAvailabilities[0].set(0);
    }

    int totalLength = ((int) Math.pow(4, levels + 1) - 1) / 3; // TODO vérifier

    return new Availability(concatenatedAvailabilities, totalLength, isChildren);
  }
}
