package org.apache.baremaps.tdtiles;

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

  /**
   * Record to store the availability of a subtree level
   *
   * @param tileAvailability
   * @param contentAvailability
   * @param availableCount
   * @param childSubtreeAvailability
   * @param level
   */
  private record SubtreeLevel(BitSet tileAvailability, BitSet contentAvailability, int availableCount, BitSet childSubtreeAvailability,
                              int level) {
  }
  
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
    byte[] subtree = readSubtree(mortonIndex, level); // TODO reput that
    if (subtree != null) {
      System.out.println("td_subtrees: Subtree found in db: " + level + "." + x + "." + y);
      return subtree;
    }

    // Subtree not found

    // Create subtree
    SubtreeLevel newSubtree;
    if (level == maxLevel - subtreeLevels + 1) {
      newSubtree = createMaxRankSubtree(mortonIndex, level);
    } else {
      newSubtree = createSubtree(mortonIndex, level, level);
    }
    byte[] binary = subtreeToJSONBinary(newSubtree);

    // Update the subtree in the database
//    updateSubtree(mortonIndex, level, binary); // TODO reput that

    // Transform subtree to binary format
    if (!newSubtree.contentAvailability().isEmpty() || !newSubtree.tileAvailability().isEmpty() || !newSubtree.childSubtreeAvailability().isEmpty()) {
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
  private byte[] subtreeToJSONBinary(SubtreeLevel subtree) {
    ByteWrapper buffer = new ByteWrapper(new byte[0]);
    JSONObject subtreeJSON = subtreeToJSON(subtree, buffer);
    String jsonString = subtreeJSON.toJSONString();

    int magic = 0x74627573;
    int version = 1;

    return createBinaryHeader(magic, version, jsonString, buffer.getBuffer());
  }

  // See: https://docs.ogc.org/cs/22-025r4/22-025r4.html#toc38
  private JSONObject subtreeToJSON(SubtreeLevel subtree, ByteWrapper buffer) {
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
    if (subtree.tileAvailability().isEmpty()) {
      tileAvailability.put("constant", 0);
    } else if (subtree.tileAvailability().cardinality() == length) {
      tileAvailability.put("constant", 1);
    } else {
      tileAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(longBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.tileAvailability().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = longBitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * longBitstreamLength;
      numberOfBitstreams++;
    }

    // Content availability
    JSONObject contentAvailability = new JSONObject();
    JSONArray contentAvailabilityArray = new JSONArray();
    boolean contentAvailabilityConstant = true;
    if (subtree.contentAvailability().isEmpty()) {
      contentAvailability.put("constant", 0);
    } else if (subtree.contentAvailability().cardinality() == length) {
      contentAvailability.put("constant", 1);
    } else {
      contentAvailabilityConstant = false;
      JSONObject contentAvailabilityObject = new JSONObject();
      contentAvailabilityObject.put("bitstream", numberOfBitstreams);
      contentAvailabilityObject.put("availableCount", subtree.availableCount());
      contentAvailabilityArray.add(contentAvailabilityObject);

      ByteBuffer tmp = ByteBuffer.allocate(longBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.tileAvailability().toByteArray());
      tmpBuffers[numberOfBitstreams] = tmp.array();
      bitstreamLengths[numberOfBitstreams] = longBitstreamLength;
      bitstreamOffsets[numberOfBitstreams] = numberOfBitstreams * longBitstreamLength;
      numberOfBitstreams++;
    }

    // Child subtree availability
    JSONObject childSubtreeAvailability = new JSONObject();
    if (subtree.childSubtreeAvailability().isEmpty()) {
      childSubtreeAvailability.put("constant", 0);
    } else if (subtree.childSubtreeAvailability().cardinality() == length) {
      childSubtreeAvailability.put("constant", 1);
    } else {
      childSubtreeAvailability.put("bitstream", numberOfBitstreams);
      ByteBuffer tmp = ByteBuffer.allocate(shortBitstreamLength);
//      tmp.order(ByteOrder.LITTLE_ENDIAN);
      tmp.put(subtree.tileAvailability().toByteArray());
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
    internalBuffer.put("name", "Internal Buffer");
    internalBuffer.put("byteLength", totalLength);
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
    byteBuffer.putLong(JSONByteLength);
    byteBuffer.putLong(buffer.length);

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

  private SubtreeLevel createMaxRankSubtree(int mortonIndex, int globalLevel) throws TileStoreException {
//    System.out.println("Creating max rank subtree at level " + globalLevel + " with coordinates: " + mortonIndexToXY(mortonIndex, globalLevel)[0] + ", " + mortonIndexToXY(mortonIndex, globalLevel)[1]);
    // Recursively create the subtree
    if (globalLevel != maxLevel) {
      int childBaseIndex = mortonIndex << 2;
      int[] childIndices = new int[4];
      SubtreeLevel[] subtrees = new SubtreeLevel[4];
      for (int i = 0; i < 4; i++) {
        childIndices[i] = childBaseIndex + i;
        subtrees[i] = createMaxRankSubtree(childIndices[i], globalLevel + 1);
      }
      return concatenateSubtreeLevel(subtrees,  globalLevel + 1);
    }

    // Create the leaf
    int[] coords = mortonIndexToXY(mortonIndex, globalLevel);
    int x = coords[0];
    int y = coords[1];

    // Get the amount of buildings in the tile
    try {
      int buildingAmount = readBuildingCount(x, y, globalLevel);
      BitSet subtreeAvailability = new BitSet();
      subtreeAvailability.set(0, buildingAmount > 0);
      if (buildingAmount > 0) {
        System.out.println("Created a max rank subtree leaf with building amount = " + buildingAmount);
      }
      return new SubtreeLevel(subtreeAvailability, subtreeAvailability, buildingAmount, new BitSet(), globalLevel);

    } catch (TileStoreException e) {
      throw new TileStoreException(e);
    }
  }

  private SubtreeLevel createSubtree(int mortonIndex, int globalLevel, int originLevel) throws TileStoreException {
    // Recursively create the subtree
    if (globalLevel < originLevel + subtreeLevels - 1) { // if not last level
      int childBaseIndex = mortonIndex << 2;
      int[] childIndices = new int[4];
      SubtreeLevel[] subtrees = new SubtreeLevel[4];
      for (int i = 0; i < 4; i++) {
        childIndices[i] = childBaseIndex + i;
        subtrees[i] = createSubtree(childIndices[i], globalLevel + 1, originLevel);
      }
      return concatenateSubtreeLevel(subtrees, globalLevel + 1);
    }

    // Last level of the Subtree. This is the first level of another Subtree.

    // Querry the database
    byte[] binary = readSubtree(mortonIndex, globalLevel + 1); // TODO reput that
    if (binary != null) {
      try {
        return getSimplifiedSubtree(readSubtreeFromBinary(binary, globalLevel + 1));
      } catch (ParseException e) {
        System.out.println("Error reading subtree binary at level " + (globalLevel + 1) + ", " + mortonIndex + " : " + e);
      }
    }

    // OK
//    System.out.println("Creating subtree at level " + level + " with coordinates: " + mortonIndexToXY(mortonIndex, level)[0] + ", " + mortonIndexToXY(mortonIndex, level)[1]);

    // Change method depending if max rank
    if (globalLevel >= maxLevel - subtreeLevels) {
//      System.out.println("New max rank subtree");
      SubtreeLevel result = createMaxRankSubtree(mortonIndex, globalLevel + 1);
//      updateSubtree(mortonIndex, level + 1, subtreeToJSONBinary(result));
      return getSimplifiedSubtree(result);
    } else {
//      System.out.println("New subtree");
      SubtreeLevel result = createSubtree(mortonIndex, globalLevel + 1, globalLevel + 1);
//      updateSubtree(mortonIndex, level + 1, subtreeToJSONBinary(result));
      return getSimplifiedSubtree(result);
    }
  }

  public SubtreeLevel readSubtreeFromBinary(byte[] binary, int level) throws ParseException {
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

    byte[] tileAvailabilityBytes = Base64.getDecoder().decode((String) subtreeJSON.get("tileAvailability"));
    BitSet tileAvailability = BitSet.valueOf(tileAvailabilityBytes);

    byte[] childSubtreeAvailabilityBytes = Base64.getDecoder().decode((String) subtreeJSON.get("childSubtreeAvailability"));
    BitSet childSubtreeAvailability = BitSet.valueOf(childSubtreeAvailabilityBytes);

    // Get the first object in the contentAvailability array
    JSONArray contentAvailabilityArray = (JSONArray) subtreeJSON.get("contentAvailability");
    JSONObject contentAvailabilityObject = (JSONObject) contentAvailabilityArray.get(0);
    byte[] contentAvailabilityBytes = Base64.getDecoder().decode((String) contentAvailabilityObject.get("bitstream"));
    BitSet contentAvailability = BitSet.valueOf(contentAvailabilityBytes);

    int maxContentAmount = ((Long) contentAvailabilityObject.get("availableCount")).intValue();

    return new SubtreeLevel(tileAvailability, contentAvailability, maxContentAmount, childSubtreeAvailability, level);
  }

  private static SubtreeLevel getSimplifiedSubtree(SubtreeLevel subtree) {
    int tileAvailability = subtree.tileAvailability().isEmpty() ? 0 : 1;
    int contentAvailability = subtree.contentAvailability().isEmpty() ? 0 : 1;
//    int childSubtreeAvailability = subtree.childSubtreeAvailability() == 0 ? 0 : 1;

    BitSet tileBitSet = new BitSet();
    tileBitSet.set(0, tileAvailability == 1);
    BitSet contentBitSet = new BitSet();
    contentBitSet.set(0, contentAvailability == 1);

    return new SubtreeLevel(tileBitSet, contentBitSet, contentAvailability, tileBitSet,
        subtree.level() - 1);
  }

  private SubtreeLevel concatenateSubtreeLevel(SubtreeLevel[] subtrees, int childGlobalLevel) {
    if (subtrees.length != 4) {
      throw new IllegalArgumentException("The subtrees array must have exactly 4 elements.");
    }
    BitSet[] tileAvailability = new BitSet[4];
    BitSet[] contentAvailability = new BitSet[4];
    BitSet[] childSubtreeAvailability = new BitSet[4];
    int totalContentAmount = 0;
    for (int i = 0; i < 4; i++) {
      totalContentAmount += subtrees[i].availableCount();
      tileAvailability[i] = subtrees[i].tileAvailability();
      contentAvailability[i] = subtrees[i].contentAvailability();
      childSubtreeAvailability[i] = subtrees[i].childSubtreeAvailability();
    }

    int childLocalLevel = childGlobalLevel % subtreeLevels;

    BitSet parentTileAvailability = concatenateBitStreams(tileAvailability, childLocalLevel, true);
    BitSet parentContentAvailability = concatenateBitStreams(contentAvailability, childLocalLevel, true);
    BitSet parentChildSubtreeAvailability = concatenateBitStreams(childSubtreeAvailability, childLocalLevel, false);

    return new SubtreeLevel(parentTileAvailability, parentContentAvailability, totalContentAmount, parentChildSubtreeAvailability,
        childGlobalLevel - 1);
  }

  private byte[] readSubtree(int mortonIndex, int globalLevel) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         Statement statement = connection.createStatement()) {
      String sql = String.format(GET_SUBTREE_QUERY, mortonIndex, globalLevel);
      logger.debug("Executing query: {}", sql);
      try (ResultSet resultSet = statement.executeQuery(sql)) {
        if (resultSet.next()) {
          byte[] binary = resultSet.getBytes("binary_file");
//          System.out.println("td_subtrees: Subtree " + mortonIndex + " found in db");
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
//        System.out.println("td_subtrees: Subtree updated in db: " + mortonIndex);
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

  private BitSet concatenateBitStreams(BitSet[] bitstreams, int childLocalLevel, boolean putActivationBit) {
    if (bitstreams.length != 4) {
      throw new IllegalArgumentException("The bitstreams array must have exactly 4 BitSet elements.");
    }

    BitSet result = new BitSet();
    int length;
    if (putActivationBit) {
      length = ((int) Math.pow(4, childLocalLevel + 1) - 1) / 3;
    } else {
      length = (int) Math.pow(4, childLocalLevel);
    }

    boolean activated = false;
    int offset = putActivationBit ? 1 : 0;

    // Append all bitstreams
    for (int i = 0; i < 4; i++) {
      for (int j = bitstreams[i].nextSetBit(0); j >= 0; j = bitstreams[i].nextSetBit(j + 1)) {
        result.set(j + (length * i) + offset);
      }
      if (!bitstreams[i].isEmpty()) {
        activated = true;
      }
    }

    if (putActivationBit) {
      // Append the activation bit at position 0
      result.set(0, activated);
    }

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


