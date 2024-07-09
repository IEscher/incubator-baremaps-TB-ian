package org.apache.baremaps.tdtiles.Subtree;


import java.util.BitSet;

public class Subtree {

  private final Availability tileAvailability;
  private final Availability contentAvailability;
  private final int availableCount;
  private final Availability childSubtreeAvailability;
  private final int levels;

  public Subtree(Availability tileAvailability, Availability contentAvailability, int availableCount,
                 Availability childSubtreeAvailability, int levels) {
    this.tileAvailability = tileAvailability;
    this.contentAvailability = contentAvailability;
    this.availableCount = availableCount;
    this.childSubtreeAvailability = childSubtreeAvailability;
    this.levels = levels;
    if (levels != tileAvailability.getAvailabilities().length
        || levels != contentAvailability.getAvailabilities().length) {
      throw new IllegalArgumentException("The number of levels (" + levels +
          ") must be equal to the number of availabilities (tile: " + tileAvailability.getAvailabilities().length +
          ", content: " + contentAvailability.getAvailabilities().length + ").");
    }
  }

  public Availability getTileAvailability() {
    return tileAvailability;
  }

  public BitSet getTileBitSet() {
    return tileAvailability.getBitSet();
  }

  public Availability getContentAvailability() {
    return contentAvailability;
  }

  public BitSet getContentBitSet() {
    return contentAvailability.getBitSet();
  }

  public int getAvailableCount() {
    return availableCount;
  }

  public Availability getChildSubtreeAvailability() {
    return childSubtreeAvailability;
  }

  public BitSet getChildSubtreeBitSet() {
    return childSubtreeAvailability.getBitSet();
  }

  public BitSet getSimplifiedChildSubtreeBitSet() {
    BitSet simplifiedBitSet = new BitSet(4);
    for (int i = 0; i < 4; i++) {
      simplifiedBitSet.set(i, !childSubtreeAvailability.getBitSet().get(i * 4, (i + 1) * 4).isEmpty());
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

    Availability[] tileAvailabilities = new Availability[4];
    Availability[] contentAvailabilities = new Availability[4];
    Availability[] childSubtreeAvailabilities = new Availability[4];
    for (int i = 0; i < 4; i++) {
      tileAvailabilities[i] = subtrees[i].getTileAvailability();
      contentAvailabilities[i] = subtrees[i].getContentAvailability();
      childSubtreeAvailabilities[i] = subtrees[i].getChildSubtreeAvailability();
    }

    Availability parentTileAvailability = Availability.concatenateAvailabilities(tileAvailabilities, false);
    Availability parentContentAvailability = Availability.concatenateAvailabilities(contentAvailabilities, false);
    Availability parentChildSubtreeAvailability = Availability.concatenateAvailabilities(childSubtreeAvailabilities, true);
    int contentCount = parentContentAvailability.getBitSet().cardinality();

    return new Subtree(parentTileAvailability, parentContentAvailability, contentCount, parentChildSubtreeAvailability,
        subtrees[0].getLevels() + 1);
  }

  public static Subtree getSimplifiedSubtree(Subtree subtree) {

    BitSet tileBitSet = new BitSet(1);
    tileBitSet.set(0, subtree.getTileAvailability().isAvailable());
    BitSet contentBitSet = new BitSet(1);
    contentBitSet.set(0, subtree.getContentAvailability().isAvailable());
    BitSet childSubtreeBitSet = subtree.getSimplifiedChildSubtreeBitSet();

    return new Subtree(
        new Availability(tileBitSet, 1, false),
        new Availability(contentBitSet, 1, false),
        subtree.getContentAvailability().isAvailable() ? 1 : 0,
        new Availability(childSubtreeBitSet, 4, true),
        1
    );
  }

  public void displayTileAvailability() {
    for (int i = 0; i < tileAvailability.getAvailabilities().length; i++) {
      System.out.println("Level " + i + ": " + tileAvailability.getAvailability(i).toString());
    }
    System.out.println("All levels: " + tileAvailability.getBitSet().toString());
  }
}
