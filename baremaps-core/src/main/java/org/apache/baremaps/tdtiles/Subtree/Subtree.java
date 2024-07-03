package org.apache.baremaps.tdtiles.Subtree;


import java.util.BitSet;

public class Subtree {

  private final Availability tileAvailability;
  private final Availability contentAvailability;
  private final int availableCount;
  private final Availability childSubtreeAvailability;
  private final int level;

  public Subtree(Availability tileAvailability, Availability contentAvailability, int availableCount,
                 Availability childSubtreeAvailability, int level) {
    this.tileAvailability = tileAvailability;
    this.contentAvailability = contentAvailability;
    this.availableCount = availableCount;
    this.childSubtreeAvailability = childSubtreeAvailability;
    this.level = level;
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

  public int getLevel() {
    return level;
  }

  public Subtree concatenateSubtreeLevel(Subtree[] subtrees, int childGlobalLevel) {
    if (subtrees.length != 4) {
      throw new IllegalArgumentException("The subtrees array must have exactly 4 elements.");
    }
    BitSet[] tileAvailability = new BitSet[4];
    BitSet[] contentAvailability = new BitSet[4];
    BitSet[] childSubtreeAvailability = new BitSet[4];
    int totalContentAmount = 0;
    for (int i = 0; i < 4; i++) {
      totalContentAmount += subtrees[i].getAvailableCount();
      tileAvailability[i] = subtrees[i].getTileAvailability();
      contentAvailability[i] = subtrees[i].getContentAvailability();
      childSubtreeAvailability[i] = subtrees[i].getChildSubtreeAvailability();
    }

    int childLocalLevel = childGlobalLevel % subtreeLevels;

    BitSet parentTileAvailability = Availability.concatenateAvailabilities(tileAvailability, childLocalLevel, true);
    BitSet parentContentAvailability = Availability.concatenateAvailabilities(contentAvailability, childLocalLevel, true);
    BitSet parentChildSubtreeAvailability = Availability.concatenateAvailabilities(childSubtreeAvailability, childLocalLevel, false);

    return new Subtree(parentTileAvailability, parentContentAvailability, totalContentAmount, parentChildSubtreeAvailability,
        childGlobalLevel - 1);
  }

  public static Subtree getSimplifiedSubtree(Subtree subtree) {
    int tileAvailability = subtree.getTileAvailability().isEmpty() ? 0 : 1;
    int contentAvailability = subtree.getContentAvailability().isEmpty() ? 0 : 1;
//    int childSubtreeAvailability = subtree.childSubtreeAvailability() == 0 ? 0 : 1;

    BitSet tileBitSet = new BitSet();
    tileBitSet.set(0, tileAvailability == 1);
    BitSet contentBitSet = new BitSet();
    contentBitSet.set(0, contentAvailability == 1);

    return new Subtree(tileBitSet, contentBitSet, contentAvailability, tileBitSet,
        subtree.level() - 1);
  }
}
