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

package org.apache.baremaps.tdtiles;

import static org.apache.baremaps.tdtiles.utils.MortonIndexes.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.BitSet;
import javax.sql.DataSource;
import org.apache.baremaps.tdtiles.Subtree.Availability;
import org.apache.baremaps.tdtiles.Subtree.Subtree;
import org.apache.baremaps.tilestore.TileStoreException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdSubtreeStore {

  private final DataSource datasource;
  private final int minLevel;
  private final int maxLevel;
  private final int subtreeLevels;
  private final int rankAmount;
  private final boolean reloadSubtrees;
  private final boolean generateAllSubtrees;

  private static final Logger logger = LoggerFactory.getLogger(TdTilesStore.class);

  private static final String GET_SUBTREE_QUERY =
      "SELECT binary_file\n" +
          "FROM td_subtrees \n" +
          "WHERE morton_index = %1$s AND level = %2$s";

  private static final String UPDATE_SUBTREE_QUERY =
      "INSERT INTO td_subtrees (morton_index, level, binary_file) \n" +
          "VALUES (?, ?, ?) \n" +
          "ON CONFLICT (morton_index, level) DO UPDATE SET \n" +
          "binary_file = EXCLUDED.binary_file";

  private static class ByteWrapper {
    byte[] buffer;

    public ByteWrapper(byte[] buffer) {
      this.buffer = buffer;
    }

    public byte[] getBuffer() {
      return buffer;
    }

    public void setBuffer(byte[] buffer) {
      this.buffer = buffer;
    }
  }

  public TdSubtreeStore(DataSource datasource, int minLevel, int maxLevel,
                        int subtreeLevels, int rankAmount, boolean reloadSubtrees, boolean generateAllSubtrees) {
    this.datasource = datasource;
    this.minLevel = minLevel;
    this.maxLevel = maxLevel;
    this.subtreeLevels = subtreeLevels;
    this.rankAmount = rankAmount;
    this.reloadSubtrees = reloadSubtrees;
    this.generateAllSubtrees = generateAllSubtrees;
  }

  public byte[] getSubtree(int level, long x, long y) throws TileStoreException {
    if (!isRoot(level)) {
      throw new TileStoreException(
          String.format("The subtree at level %d is not a root of a subtree.", level));
    }
    System.out.println("Getting subtree: " + level + "__" + x + "_" + y);

    // Search in db
    long mortonIndex = interleaveBits(x, y, level);
    if (!reloadSubtrees) {
      byte[] subtree = readSubtree(mortonIndex, level);
      if (subtree != null) {
         System.out.println("td_subtrees: Subtree found in db: " + level + "__" + x + "_" + y);
        return subtree;
      }
    }

    // Subtree isn't found
    System.out.println("Creating subtree: " + level + "__" + x + "_" + y);

    // Create subtree
    Subtree newSubtree;
    if (level == maxLevel - subtreeLevels + 1) {
      newSubtree = createMaxRankSubtree(mortonIndex);
    } else {
      newSubtree = createSubtree(mortonIndex, level, level);
    }
    newSubtree.displayTileAvailability();
    newSubtree.displayChildAvailability();
    // Transform subtree to binary format
    byte[] binary = subtreeToJSONBinary(newSubtree); // Getting subtree at level 5 with coordinates:
                                                     // 16, 24

    // Update the subtree in the database
    updateSubtree(mortonIndex, level, binary);

    return binary;
  }

  private boolean isRoot(int level) {
    for (int i = 0; i < rankAmount; i++) {
      if (level == i * subtreeLevels) {
        return true;
      }
    }
    return false;
  }

  // See: https://docs.ogc.org/cs/22-025r4/22-025r4.html#toc39
  private byte[] subtreeToJSONBinary(Subtree subtree) {
    ByteWrapper buffer = new ByteWrapper(new byte[0]);
    JSONObject subtreeJSON = subtreeToJSON(subtree, buffer);
    String jsonString = subtreeJSON.toJSONString();

    int magic = 0x74627573;
    int version = 1;

    return createBinaryHeader(magic, version, jsonString, buffer.getBuffer());
  }

  // See: https://docs.ogc.org/cs/22-025r4/22-025r4.html#toc38
  private JSONObject subtreeToJSON(Subtree subtree, ByteWrapper buffer) {
    int length = ((int) Math.pow(4, subtreeLevels) - 1) / 3;
    int lengthChildren = (int) Math.pow(4, subtreeLevels);
    int bitstreamLength = (int) Math.ceil((double) length / 8.0);
    int childrenBitstreamLength = (int) Math.ceil((double) lengthChildren / 8.0);
    int numberOfBitstreams = 0;
    int[] bitstreamLengths = new int[3];
    int[] bitstreamOffsets = new int[3];

    JSONObject json = new JSONObject();
    byte[][] tmpBuffers = new byte[3][];

    // Tile availability
    JSONObject tileAvailability = new JSONObject();
    tileAvailability.put("availableCount", subtree.getTileBitSet().cardinality());
    if (subtree.getTileBitSet().isEmpty()) {
      tileAvailability.put("constant", 0);
    } else if (subtree.getTileBitSet().cardinality() == length) {
      tileAvailability.put("constant", 1);
    } else {
      tileAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(bitstreamLength);
      // tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getTileBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = bitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * bitstreamLength;
      numberOfBitstreams++;
    }

    // Content availability
    JSONArray contentAvailabilityArray = new JSONArray();
    boolean contentPresent = true;
    if (subtree.getContentBitSet().isEmpty()) {
      contentPresent = false;
    } else if (subtree.getContentBitSet().cardinality() == length) {
      JSONObject contentAvailabilityObject = new JSONObject();
      contentAvailabilityObject.put("constant", 1);
      contentAvailabilityObject.put("availableCount", subtree.getContentBitSet().cardinality());
      contentAvailabilityArray.add(contentAvailabilityObject);
    } else {
      JSONObject contentAvailabilityObject = new JSONObject();
      contentAvailabilityObject.put("bitstream", numberOfBitstreams);
      contentAvailabilityObject.put("availableCount", subtree.getContentBitSet().cardinality());
      contentAvailabilityArray.add(contentAvailabilityObject);

      ByteBuffer tmp = ByteBuffer.allocate(bitstreamLength);
      // tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getContentBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = bitstreamLength;
      for (int i = 0; i < numberOfBitstreams; i++) {
        bitstreamOffsets[numberOfBitstreams] += bitstreamLengths[i];
      }
      // bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * bitstreamLength;
      numberOfBitstreams++;
    }

    // Child subtree availability
    JSONObject childSubtreeAvailability = new JSONObject();
    childSubtreeAvailability.put("availableCount", subtree.getChildSubtreeBitSet().cardinality());
    if (subtree.getChildSubtreeBitSet().isEmpty()) {
      childSubtreeAvailability.put("constant", 0);
    } else if (subtree.getChildSubtreeBitSet().cardinality() == lengthChildren) {
      childSubtreeAvailability.put("constant", 1);
    } else {
      childSubtreeAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(childrenBitstreamLength);
      // tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getChildSubtreeBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = childrenBitstreamLength;
      for (int i = 0; i < numberOfBitstreams; i++) {
        bitstreamOffsets[numberOfBitstreams] += bitstreamLengths[i];
      }
      // bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * childrenBitstreamLength;
      numberOfBitstreams++;
    }


    // Put the temporary buffers into the buffer of the adequate size
    int totalLength = 0;
    for (int i = 0; i < numberOfBitstreams; i++) {
      totalLength += bitstreamLengths[i];
    }
    byte[] availabilityBuffer = new byte[totalLength];
    for (int i = 0; i < numberOfBitstreams; i++) {
      System.arraycopy(tmpBuffers[i], 0, availabilityBuffer, bitstreamOffsets[i],
          bitstreamLengths[i]);
    }
    buffer.setBuffer(availabilityBuffer);

    // buffer
    JSONArray buffers = new JSONArray();
    JSONObject internalBuffer = new JSONObject();
    int bufferPadding = (8 - totalLength % 8) % 8;
    internalBuffer.put("name", "Internal Buffer");
    internalBuffer.put("byteLength", totalLength + bufferPadding);
    buffers.add(internalBuffer);
    json.put("buffers", buffers);

    // bufferViews
    JSONArray bufferViews = new JSONArray();
    for (int i = 0; i < numberOfBitstreams; i++) {
      JSONObject bufferView = new JSONObject();
      bufferView.put("buffer", 0);
      bufferView.put("byteOffset", bitstreamOffsets[i]);
      bufferView.put("byteLength", bitstreamLengths[i]);
      bufferViews.add(bufferView);
    }
    json.put("bufferViews", bufferViews);

    json.put("tileAvailability", tileAvailability);
    // if (contentAvailabilityConstant) {
    // json.put("contentAvailability", contentAvailability);
    // } else {
    // json.put("contentAvailability", contentAvailabilityArray);
    // }
    if (contentPresent) {
      json.put("contentAvailability", contentAvailabilityArray);
    }
    json.put("childSubtreeAvailability", childSubtreeAvailability);

    return json;
  }

  private static byte[] createBinaryHeader(int magic, int version,
      String jsonString, byte[] buffer) {
    long JSONByteLength = jsonString.getBytes(StandardCharsets.UTF_8).length;
    int jsonPadding = (8 - (int) JSONByteLength % 8) % 8;
    int bufferPadding = (8 - buffer.length % 8) % 8;

    // Create ByteBuffer with the correct capacity
    ByteBuffer byteBuffer = ByteBuffer.allocate(4 + 4 + 8 + 8 + (int) JSONByteLength + jsonPadding +
        buffer.length + bufferPadding);
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

    // Write data
    byteBuffer.putInt(magic);
    byteBuffer.putInt(version);
    byteBuffer.putLong(JSONByteLength + (long) jsonPadding);
    byteBuffer.putLong((long) buffer.length + (long) bufferPadding);

    byteBuffer.put(jsonString.getBytes(StandardCharsets.UTF_8));
    for (int i = 0; i < jsonPadding; i++) {
      byteBuffer.put((byte) 0x20); // Padding with spaces
    }

    byteBuffer.put(buffer);
    for (int i = 0; i < bufferPadding; i++) {
      byteBuffer.put((byte) 0x00); // Padding with zeros
    }

    return byteBuffer.array();
  }

  private Subtree createMaxRankSubtree(long mortonIndex) throws TileStoreException {
    int globalLevel = maxLevel - subtreeLevels + 1;
    int totalLeaves = (int) Math.pow(4, subtreeLevels - 1);
    BitSet buildingPresenceBitSet = new BitSet(totalLeaves);

    long baseXY[] = mortonIndexToXY(mortonIndex, globalLevel);
    if (readBuildingCount(baseXY[0], baseXY[1], globalLevel, datasource, logger)) {
      long baseIndex = mortonIndex << (2 * (subtreeLevels - 1));
      for (int i = 0; i < totalLeaves; i++) {
        long[] xy = mortonIndexToXY(baseIndex + i, maxLevel);
        // if(readBuildingCount(xy[0], xy[1], maxLevel) > 0) {
        // System.out.println("building at " + xy[0] + ", " + xy[1]);
        // }
        buildingPresenceBitSet.set(i, readBuildingCount(xy[0], xy[1], maxLevel, datasource, logger));
      }
    }

    Availability tileAvailability =
        Availability.generateTileAvailability(buildingPresenceBitSet, totalLeaves);
    Availability contentAvailability = Availability.generateContentAvailability(
        buildingPresenceBitSet, totalLeaves, subtreeLevels - (maxLevel - minLevel));
    Availability childSubtreeAvailability =
        new Availability(new BitSet(), (int) Math.pow(4, subtreeLevels), true);

    return new Subtree(tileAvailability, contentAvailability, childSubtreeAvailability,
        subtreeLevels);
  }

  private Subtree createSubtree(long mortonIndex, int globalLevel, int originLevel)
      throws TileStoreException {
    // Check if there are any buildings in the tile
    long[] coords = mortonIndexToXY(mortonIndex, globalLevel);
    if (!readBuildingCount(coords[0], coords[1], globalLevel, datasource, logger)) {
      // System.out.println("----------- No buildings in tile at " + globalLevel + "__" + coords[0]
      // + "_" + coords[1]);
      int levels = subtreeLevels - (globalLevel - originLevel);
      int totalLength = ((int) Math.pow(4, levels) - 1) / 3;
      int totalChildren = (int) Math.pow(4, levels);
      return new Subtree(
          new Availability(new BitSet(), totalLength, false),
          new Availability(new BitSet(), totalLength, false),
          new Availability(new BitSet(), totalChildren, true),
          levels);
    }

    // Recursively create the subtree
    if (globalLevel < originLevel + subtreeLevels - 1) { // if not last level
      long childBaseIndex = mortonIndex << 2;
      Subtree[] subtrees = new Subtree[4];
      for (int i = 0; i < 4; i++) {
        subtrees[i] = createSubtree(childBaseIndex + i, globalLevel + 1, originLevel);
      }
      return Subtree.concatenateSubtreeLevel(subtrees);
    }

    // Last level of the Subtree. This is the first level of another Subtree.

    Subtree[] subtrees = new Subtree[4];
    long childBaseIndex = mortonIndex << 2;
    for (int i = 0; i < 4; i++) {
      Subtree childSubtree;
      if (generateAllSubtrees) {
        // Change method depending if max rank
        if (globalLevel >= maxLevel - subtreeLevels) {
          childSubtree = createMaxRankSubtree(childBaseIndex + i);
        } else {
          childSubtree = createSubtree(childBaseIndex + i, globalLevel + 1, globalLevel + 1);
        }
        subtrees[i] = Subtree.getSimplifiedSubtree(childSubtree);
        updateSubtree(childBaseIndex + i, globalLevel + 1, subtreeToJSONBinary(childSubtree));
      } else { // Read from db to create simple subtree
        long[] xy = mortonIndexToXY(childBaseIndex + i, globalLevel + 1);
        BitSet presenceBitSet = new BitSet(1);
        BitSet contentBitSet = new BitSet(1);
        if (readBuildingCount(xy[0], xy[1], globalLevel + 1, datasource, logger)) {
          presenceBitSet.set(0);
          // If max rank
          if (globalLevel >= maxLevel - subtreeLevels) {
            contentBitSet.set(0);
          }
        }
        subtrees[i] = new Subtree(
            new Availability(presenceBitSet, 1, false),
            new Availability(contentBitSet, 1, false),
            // Note: We do not care to properly set the childSubtreeAvailability since it will be
            // concatenated and simplified. The information would not be used.
            new Availability(new BitSet(), 4, true),
            1);
      }
    }
    Subtree newSubtree = Subtree.getSimplifiedSubtree(Subtree.concatenateSubtreeLevel(subtrees));
    return newSubtree;
  }

  private byte[] readSubtree(long mortonIndex, int globalLevel) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
        Statement statement = connection.createStatement()) {
      String sql = String.format(GET_SUBTREE_QUERY, mortonIndex, globalLevel);
      logger.debug("Executing query: {}", sql);
      try (ResultSet resultSet = statement.executeQuery(sql)) {
        if (resultSet.next()) {
          byte[] binary = resultSet.getBytes("binary_file");
          // System.out.println("td_subtrees: Subtree " + mortonIndex + " found in db");
          return binary;
        } else {
          // System.out.println("td_subtrees: Subtree " + mortonIndex + " not found in db");
          return null;
        }
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  private void updateSubtree(long mortonIndex, int globalLevel, byte[] binary)
      throws TileStoreException {
    try (Connection connection = datasource.getConnection()) {
      logger.debug("Executing query: {}", UPDATE_SUBTREE_QUERY);
      try (
          PreparedStatement preparedStatement = connection.prepareStatement(UPDATE_SUBTREE_QUERY)) {
        preparedStatement.setLong(1, mortonIndex);
        preparedStatement.setInt(2, globalLevel);
        preparedStatement.setBytes(3, binary);
        preparedStatement.executeUpdate();
        // System.out.println("td_subtrees: Subtree updated in db: " + mortonIndex);
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }
}
