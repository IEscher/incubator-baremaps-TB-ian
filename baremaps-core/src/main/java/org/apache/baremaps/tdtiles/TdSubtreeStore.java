package org.apache.baremaps.tdtiles;

import org.apache.baremaps.tdtiles.Subtree.Availability;
import org.apache.baremaps.tilestore.TileStoreException;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.simple.JSONObject;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;
import java.util.BitSet;

import org.apache.baremaps.tdtiles.Subtree.Subtree;

public class TdSubtreeStore {

  private final DataSource datasource;
  private final int maxCompression;
  private final int minLevel;
  private final int maxLevel;
  private final int[] compressionLevels;
  private final int availableLevels;
  private final int subtreeLevels;
  private final int rankAmount;

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

  private static final String GET_BUILDINGS_AMOUNT_QUERY =
      "select count(*) " +
          "from osm_ways where (tags ? 'building' or tags ? 'building:part') and " +
          "st_intersects(geom, st_makeenvelope(%1$s, %2$s, %3$s, %4$s, 4326))";
  
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

  public TdSubtreeStore(DataSource datasource, int maxCompression, int minLevel, int maxLevel, int[] compressionLevels, int availableLevels, int subtreeLevels, int rankAmount) {
    this.datasource = datasource;
    this.maxCompression = maxCompression;
    this.minLevel = minLevel;
    this.maxLevel = maxLevel;
    this.compressionLevels = compressionLevels;
    this.availableLevels = availableLevels;
    this.subtreeLevels = subtreeLevels;
    this.rankAmount = rankAmount;
  }

  public byte[] getSubtree(int level, int x, int y) throws TileStoreException {
    if (!isRoot(level)) {
      throw new TileStoreException(String.format("The subtree at level %d is not a root of a subtree.", level));
    }
//    System.out.println("Getting subtree at level " + level + " with coordinates: " + x + ", " + y);

    // Search in db
    int mortonIndex = interleaveBits(x, y, level);
//    byte[] subtree = readSubtree(mortonIndex, level); // TODO reput that
//    if (subtree != null) {
//      System.out.println("td_subtrees: Subtree found in db: " + level + "." + x + "." + y);
//      return subtree;
//    }

    // Subtree not found

    // Create subtree
    Subtree newSubtree;
    if (level == maxLevel - subtreeLevels + 1) {
      newSubtree = createMaxRankSubtree(mortonIndex, level);
    } else {
      newSubtree = createSubtree(mortonIndex, level, level);
    }
    newSubtree.displayTileAvailability();
    byte[] binary = subtreeToJSONBinary(newSubtree);

    // Update the subtree in the database
    updateSubtree(mortonIndex, level, binary); // TODO reput that

    // Transform subtree to binary format
    if (!newSubtree.getContentBitSet().isEmpty() || !newSubtree.getTileBitSet().isEmpty() ||
        !newSubtree.getChildSubtreeBitSet().isEmpty()) {
      System.out.println("Subtree created: " + level + "." + x + "." + y);
    }
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
    int lengthShort = (int) Math.pow(4, subtreeLevels - 1);
    int longBitstreamLength = (int) Math.ceil((double) length / 8.0);
    int shortBitstreamLength = (int) Math.ceil((double) lengthShort / 8.0);
    int numberOfBitstreams = 0;
    int[] bitstreamLengths = new int[3];
    int[] bitstreamOffsets = new int[3];

    JSONObject json = new JSONObject();
    byte[][] tmpBuffers = new byte[3][];

    // Tile availability
    JSONObject tileAvailability = new JSONObject();
    if (subtree.getTileBitSet().isEmpty()) {
      tileAvailability.put("constant", 0);
    } else if (subtree.getTileBitSet().cardinality() == length) {
      tileAvailability.put("constant", 1);
    } else {
      tileAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(longBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getTileBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = longBitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * longBitstreamLength;
      numberOfBitstreams++;
    }

    // Content availability
    JSONObject contentAvailability = new JSONObject();
    JSONArray contentAvailabilityArray = new JSONArray();
    boolean contentAvailabilityConstant = true;
    if (subtree.getContentBitSet().isEmpty()) {
      contentAvailability.put("constant", 0);
    } else if (subtree.getContentBitSet().cardinality() == length) {
      contentAvailability.put("constant", 1);
    } else {
      contentAvailabilityConstant = false;
      JSONObject contentAvailabilityObject = new JSONObject();
      contentAvailabilityObject.put("bitstream", numberOfBitstreams);
      contentAvailabilityObject.put("availableCount", subtree.getAvailableCount());
      contentAvailabilityArray.add(contentAvailabilityObject);

      ByteBuffer tmp = ByteBuffer.allocate(longBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getContentBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = longBitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * longBitstreamLength;
      numberOfBitstreams++;
    }

    // Child subtree availability
    JSONObject childSubtreeAvailability = new JSONObject();
    if (subtree.getChildSubtreeBitSet().isEmpty()) {
      childSubtreeAvailability.put("constant", 0);
    } else if (subtree.getChildSubtreeBitSet().cardinality() == length) {
      childSubtreeAvailability.put("constant", 1);
    } else {
      childSubtreeAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(shortBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.getChildSubtreeBitSet().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = shortBitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * shortBitstreamLength;
      numberOfBitstreams++;
    }


    // Put the temporary buffers into the buffer of the adequate size
    int totalLength = 0;
    for (int i = 0; i < numberOfBitstreams; i++) {
      totalLength += bitstreamLengths[i];
    }
    byte[] availabilityBuffer = new byte[totalLength];
    for (int i = 0; i < numberOfBitstreams; i++) {
      System.arraycopy(tmpBuffers[i], 0, availabilityBuffer, bitstreamOffsets[i], bitstreamLengths[i]);
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
    if (contentAvailabilityConstant) {
      json.put("contentAvailability", contentAvailability);
    } else {
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

  private Subtree createMaxRankSubtree(int mortonIndex, int globalLevel) throws TileStoreException {
    int totalLeaves = (int) Math.pow(4, subtreeLevels - 1);
    BitSet buildingPresenceBitSet = new BitSet(totalLeaves);

    int baseXY[] = mortonIndexToXY(mortonIndex, globalLevel);
    if (readBuildingCount(baseXY[0], baseXY[1], globalLevel) != 0) {
      int baseIndex = mortonIndex << 2 * subtreeLevels;
      for (int i = 0; i < totalLeaves; i++) {
        int[] xy = mortonIndexToXY(baseIndex + i, maxLevel);
        buildingPresenceBitSet.set(i, readBuildingCount(xy[0], xy[1], maxLevel) > 0);
        if (readBuildingCount(xy[0], xy[1], maxLevel) > 0) {
          System.out.println("Building found at " + xy[0] + ", " + xy[1] + ": " + readBuildingCount(xy[0], xy[1], maxLevel));
        }
      }
    }

    Availability tileAvailability = Availability.generateAvailabilities(buildingPresenceBitSet, totalLeaves, false);
    Availability contentAvailability = Availability.generateAvailabilities(buildingPresenceBitSet, totalLeaves, false);
    Availability childSubtreeAvailability = Availability.generateAvailabilities(new BitSet(totalLeaves), totalLeaves, true);

    return new Subtree(tileAvailability, contentAvailability, contentAvailability.getBitSet().cardinality(), childSubtreeAvailability, subtreeLevels);
  }

//  private Subtree createMaxRankSubtree(int mortonIndex, int globalLevel) throws TileStoreException {
////    System.out.println("Creating max rank subtree at level " + globalLevel + " with coordinates: " + mortonIndexToXY(mortonIndex, globalLevel)[0] + ", " + mortonIndexToXY(mortonIndex, globalLevel)[1]);
//    // Recursively create the subtree
//    if (globalLevel != maxLevel) {
//      int childBaseIndex = mortonIndex << 2;
//      Subtree[] subtrees = new Subtree[4];
//      for (int i = 0; i < 4; i++) {
//        subtrees[i] = createMaxRankSubtree(childBaseIndex + i, globalLevel + 1);
//      }
//      return Subtree.concatenateSubtreeLevel(subtrees);
//    }
//
//    // Create the leaf
//    int[] coords = mortonIndexToXY(mortonIndex, globalLevel);
//    int x = coords[0];
//    int y = coords[1];
//
//    // Get the amount of buildings in the tile
//    try {
//      int buildingAmount = readBuildingCount(x, y, globalLevel);
//      BitSet subtreeBitSet = new BitSet(1);
//      subtreeBitSet.set(0, buildingAmount > 0);
//      if (buildingAmount > 0) {
//        System.out.println("Created a max rank subtree leaf with building amount = " + buildingAmount);
//      }
//
//      Availability subtreeAvailability = new Availability(subtreeBitSet, 1, false);
//      BitSet childSubtreeBitSet = new BitSet(4);
//      Availability childrenAvailability = new Availability(childSubtreeBitSet, 4, true);
//      return new Subtree(
//          subtreeAvailability,
//          subtreeAvailability,
//          buildingAmount > 0 ? 1 : 0,
//          childrenAvailability,
//          1
//      );
//
//    } catch (TileStoreException e) {
//      throw new TileStoreException(e);
//    }
//  }

  private Subtree createSubtree(int mortonIndex, int globalLevel, int originLevel) throws TileStoreException {
    // Recursively create the subtree
    if (globalLevel < originLevel + subtreeLevels - 1) { // if not last level
      int childBaseIndex = mortonIndex << 2;
      int[] childIndices = new int[4];
      Subtree[] subtrees = new Subtree[4];
      for (int i = 0; i < 4; i++) {
        childIndices[i] = childBaseIndex + i;
        subtrees[i] = createSubtree(childIndices[i], globalLevel + 1, originLevel);
      }
      return Subtree.concatenateSubtreeLevel(subtrees);
    }

    // Last level of the Subtree. This is the first level of another Subtree.

    // Querry the database
//    byte[] binary = readSubtree(mortonIndex, globalLevel + 1); // TODO reput that
//    if (binary != null) {
//      try {
//        return Subtree.getSimplifiedSubtree(readSubtreeFromBinary(binary, globalLevel + 1));
//      } catch (ParseException e) {
//        System.out.println("Error reading subtree binary at level " + (globalLevel + 1) + ", " + mortonIndex + " : " + e);
//      }
//    }

//    System.out.println("Creating subtree at level " + level + " with coordinates: " + mortonIndexToXY(mortonIndex, level)[0] + ", " + mortonIndexToXY(mortonIndex, level)[1]);

    // Change method depending if max rank
    if (globalLevel >= maxLevel - subtreeLevels) {
      Subtree result = createMaxRankSubtree(mortonIndex, globalLevel + 1);
      System.out.println("New max rank subtree at " + globalLevel + "__" + mortonIndexToXY(mortonIndex, globalLevel)[0] + "_" + mortonIndexToXY(mortonIndex, globalLevel)[1]);
      updateSubtree(mortonIndex, globalLevel + 1, subtreeToJSONBinary(result)); // TODO reput that
      return Subtree.getSimplifiedSubtree(result);
    } else {
      Subtree result = createSubtree(mortonIndex, globalLevel + 1, globalLevel + 1);
      System.out.println("New subtree at " + globalLevel + "__" + mortonIndexToXY(mortonIndex, globalLevel)[0] + "_" + mortonIndexToXY(mortonIndex, globalLevel)[1]);
      updateSubtree(mortonIndex, globalLevel + 1, subtreeToJSONBinary(result)); // TODO reput that
      return Subtree.getSimplifiedSubtree(result);
    }
  }

  public Subtree readSubtreeFromBinary(byte[] binary, int level) throws ParseException {
    ByteBuffer byteBuffer = ByteBuffer.wrap(binary);
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

    int magic = byteBuffer.getInt();
    int version = byteBuffer.getInt();
    long JSONByteLength = byteBuffer.getLong();
    long binaryByteLength = byteBuffer.getLong();

    byte[] jsonStringBytes = new byte[(int) JSONByteLength];
    byteBuffer.get(jsonStringBytes);
    String jsonString = new String(jsonStringBytes, StandardCharsets.UTF_8);

    JSONParser parser = new JSONParser();
    JSONObject subtreeJSON = (JSONObject) parser.parse(jsonString);

    // Extract and parse availability information from the binary data
    BitSet tileAvailabilityBitSet = extractBitSetFromBinary(byteBuffer, subtreeJSON, "tileAvailability");
    BitSet contentAvailabilityBitSet = extractBitSetFromBinary(byteBuffer, subtreeJSON, "contentAvailability");
    BitSet childSubtreeAvailabilityBitSet = extractBitSetFromBinary(byteBuffer, subtreeJSON, "childSubtreeAvailability");

    // Create Availability instances
    Availability tileAvailability = new Availability(tileAvailabilityBitSet, tileAvailabilityBitSet.length(), false);
    Availability contentAvailability = new Availability(contentAvailabilityBitSet, contentAvailabilityBitSet.length(), false);
    Availability childSubtreeAvailability = new Availability(childSubtreeAvailabilityBitSet, childSubtreeAvailabilityBitSet.length(), true);

    // Calculate available count for content
    int availableCount = contentAvailabilityBitSet.cardinality();

    // Create and return the Subtree instance
    return new Subtree(tileAvailability, contentAvailability, availableCount, childSubtreeAvailability, level);
  }

  private BitSet extractBitSetFromBinary(ByteBuffer byteBuffer, JSONObject subtreeJSON, String key) {
    JSONObject availabilityObject = (JSONObject) subtreeJSON.get(key);
    Long bitstreamIndex = (Long) availabilityObject.get("bitstream");
    if (bitstreamIndex != null) {
      int byteOffset = ((Long) ((JSONArray) subtreeJSON.get("bufferViews")).get(bitstreamIndex.intValue())).intValue();
      int byteLength = ((Long) ((JSONArray) subtreeJSON.get("bufferViews")).get(bitstreamIndex.intValue())).intValue();
      byte[] bitstreamBytes = new byte[byteLength];
      byteBuffer.position(byteOffset);
      byteBuffer.get(bitstreamBytes);
      return BitSet.valueOf(bitstreamBytes);
    } else {
      // Handle constant availability
      Long constant = (Long) availabilityObject.get("constant");
      BitSet bitSet = new BitSet(1);
      bitSet.set(0, constant != null && constant == 1);
      return bitSet;
    }
  }

  private byte[] readSubtree(int mortonIndex, int globalLevel) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         Statement statement = connection.createStatement()) {
      String sql = String.format(GET_SUBTREE_QUERY, mortonIndex, globalLevel);
      logger.debug("Executing query: {}", sql);
      try (ResultSet resultSet = statement.executeQuery(sql)) {
        if (resultSet.next()) {
          byte[] binary = resultSet.getBytes("binary_file");
          System.out.println("td_subtrees: Subtree " + mortonIndex + " found in db");
          return binary;
        } else {
//          System.out.println("td_subtrees: Subtree " + mortonIndex + " not found in db");
          return null;
        }
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  private void updateSubtree(int mortonIndex, int globalLevel, byte[] binary) throws TileStoreException {
    try (Connection connection = datasource.getConnection()) {
      logger.debug("Executing query: {}", UPDATE_SUBTREE_QUERY);
      try (PreparedStatement preparedStatement = connection.prepareStatement(UPDATE_SUBTREE_QUERY)) {
        preparedStatement.setInt(1, mortonIndex);
        preparedStatement.setInt(2, globalLevel);
        preparedStatement.setBytes(3, binary);
        preparedStatement.executeUpdate();
        System.out.println("td_subtrees: Subtree updated in db: " + mortonIndex);
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  private int readBuildingCount(int x, int y, int globalLevel) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         Statement statement = connection.createStatement()) {
      float[] coords = xyzToLatLonRadians(x, y, globalLevel);
      String sql = String.format(GET_BUILDINGS_AMOUNT_QUERY,
          coords[2] * 180 / (float) Math.PI,
          coords[0] * 180 / (float) Math.PI,
          coords[3] * 180 / (float) Math.PI,
          coords[1] * 180 / (float) Math.PI);
      logger.debug("Executing query: {}", sql);
//      System.out.println("osm_ways: " + sql);
      try (ResultSet resultSet = statement.executeQuery(sql)) {
        if (resultSet.next()) {
          int result = resultSet.getInt(1);
          if (result > 0) {
//            System.out.println("osm_ways: Buildings found: " + result);
          }
          return result;
        } else {
          return 0;
        }
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  /**
   * See: https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/AVAILABILITY.adoc
   *
   * @param x
   * @param y
   * @param level
   * @return The morton index of the two coordinates at the given level
   */
  private static int interleaveBits(int x, int y, int level) {
    int result = 0;
    int length = (int) Math.pow(2, level);
    for (int i = 0; i < length; i++) {
      result |= (x & (1 << i)) << i | (y & (1 << i)) << (i + 1);
    }
    return result;
  }

  private static int[] mortonIndexToXY(int mortonIndex, int level) {
    int[] result = new int[2];
    int length = (int) Math.pow(2, level);
    int x = 0;
    int y = 0;
    for (int i = 0; i < length; i++) {
      x |= (mortonIndex & (1 << (2 * i))) >> i;
      y |= (mortonIndex & (1 << (2 * i + 1))) >> (i + 1);
    }
    result[0] = x;
    result[1] = y;
    return result;
  }

  /**
   * Convert XYZ tile coordinates to lat/lon in radians.
   *
   * @param x
   * @param y
   * @param z
   * @return
   */
  public static float[] xyzToLatLonRadians(int x, int y, int z) {
    float[] answer = new float[4];
    int subdivision = 1 << z;
    float yWidth = (float) Math.PI / subdivision;
    float xWidth = 2 * (float) Math.PI / subdivision;
    answer[0] = -(float) Math.PI / 2 + y * yWidth; // Lon
    answer[1] = answer[0] + yWidth; // Lon max
    answer[2] = -(float) Math.PI + xWidth * x; // Lat
    answer[3] = answer[2] + xWidth; // Lat max
    // Clamp to -PI/2 to PI/2
    answer[0] = Math.max(-(float) Math.PI / 2, Math.min((float) Math.PI / 2, answer[0]));
    answer[1] = Math.max(-(float) Math.PI / 2, Math.min((float) Math.PI / 2, answer[1]));
    // Clamp to -PI to PI
    answer[2] = Math.max(-(float) Math.PI, Math.min((float) Math.PI, answer[2]));
    answer[3] = Math.max(-(float) Math.PI, Math.min((float) Math.PI, answer[3]));
    return answer;
  }

}


