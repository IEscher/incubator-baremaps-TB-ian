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
import static org.apache.baremaps.tdtiles.GltfBuilder.createGltfList;
import static org.apache.baremaps.tdtiles.GltfBuilder.createNode;
import static org.apache.baremaps.tdtiles.utils.MortonIndexes.*;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

import de.javagl.jgltf.model.NodeModel;
import org.apache.baremaps.tdtiles.building.Building;
import org.apache.baremaps.tdtiles.tileset.*;
import org.apache.baremaps.tdtiles.utils.Color;
import org.apache.baremaps.tdtiles.utils.ColorUtility;
import org.apache.baremaps.tilestore.TileStoreException;
import org.apache.baremaps.utils.GeometryUtils;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * id : 25685109 {"name": "Spörry", "height": "10.5", "roof:shape": "gabled", "roof:colour": "red",
 * "roof:levels": "1", "building:part": "yes", "roof:material": "roof_tiles", "building:colour":
 * "yellow", "building:levels": "3"}
 *
 * "roof:levels" building:levels
 */

/**
 * A read-only {@code TileStore} implementation that uses the PostgreSQL to generate 3d tiles.
 */
public class TdTilesStore {

  private static final Logger logger = LoggerFactory.getLogger(TdTilesStore.class);

  private static final String FIND_QUERY =
      "select st_asbinary(geom), " + // 1
          "tags -> 'building', " + // 2
          "tags -> 'height', " + // 3
          "tags -> 'building:levels', " + // 4
          "tags -> 'building:min_level', " + // 5
          "tags -> 'building:colour', " + // 6
          "tags -> 'building:material', " + // 7
          "tags -> 'building:part', " + // 8
          "tags -> 'roof:shape', " + // 9
          "tags -> 'roof:levels', " + // 10
          "tags -> 'roof:height', " + // 11
          "tags -> 'roof:color', " + // 12
          "tags -> 'roof:material'," + // 13
          "tags -> 'roof:angle', " + // 14
          "tags -> 'roof:direction' " + // 15
          "from osm_ways where (tags ? 'building' or tags ? 'building:part') and "
          +
          "st_intersects(geom, st_makeenvelope(%1$s, %2$s, %3$s, %4$s, 4326)) LIMIT %5$s";

  private static final String UPSERT_QUERY =
      "INSERT INTO td_tile_gltf (x, y, level, gltf_binary) " +
          "VALUES (?, ?, ?, ?) " +
          "ON CONFLICT (x, y, level) DO UPDATE SET gltf_binary = ?";

  private static final String READ_QUERY = "SELECT gltf_binary FROM td_tile_gltf WHERE x = ? AND y = ? AND level = ?";

  private static final String GET_BUILDINGS_AMOUNT_QUERY =
      "select count(*) " +
          "from osm_ways where (tags ? 'building' or tags ? 'building:part') and " +
          "st_intersects(geom, st_makeenvelope(%1$s, %2$s, %3$s, %4$s, 4326))";

  private final DataSource datasource;
  private final int maxCompression;
  private final int[] compressionLevels;
  private final int minLevel;
  private final int maxLevel;

  public TdTilesStore(DataSource datasource, int maxCompression, int[] compressionLevels, int minLevel, int maxLevel) {
    this.datasource = datasource;
    this.maxCompression = maxCompression;
    this.compressionLevels = compressionLevels;
    this.minLevel = minLevel;
    this.maxLevel = maxLevel;
  }

  public Tileset getTileset(int level, long x, long y)
      throws Exception {
    float[] coords = xyzToLatLonRadians(x, y, level);

    // Bounding volume of the tile
    float west = coords[0]; // Minimum longitude
    float south = coords[2]; // Minimum latitude
    float east = coords[1]; // Maximum longitude
    float north = coords[3]; // Maximum latitude
    BoundingVolume boundingVolume = new BoundingVolume(new Float[]{west, south, east, north, 1f, 2f});
    BoundingVolume tilesetBoundingVolume = new BoundingVolume(new Float[]{west - 0.1f, south - 0.1f, east + 0.1f, north + 0.1f, 1f, 3f});

    if (level < minLevel) {
//      Tile example = new Tile(
//          new BoundingVolume(new Float[]{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}),
//          "/content/content_building_id_1.glb",
//          0
//      );
      Tileset tileset = new Tileset(
          new Asset("1.1"),
          100f,
          new Root(
              boundingVolume,
              1024f,
              "ADD",
              new Tile[0]
//            new Tile[]{example}
          ));
      return tileset;
    }

    // Retrieve the gltf in the database if it exists
    byte[] tileExists = read(level, x, y);
    int levelDelta = maxLevel - minLevel;
    int compression = (maxLevel - level) * maxCompression / levelDelta;

    if (tileExists == null) {
//     Find the unprocessed buildings in the tile
      System.out.println("Creating tile: " + level + "__" + x + "_" + y);
      int limit = 5000;
      List<Building> buildings;
      buildings = findBuildings(coords[0], coords[1], coords[2], coords[3], limit);
      List<NodeModel> nodes = new LinkedList<>();
      for (Building building : buildings) {
        nodes.add(createNode(building, compression));
      }

      // Update the database with the tile
      byte[] glb = createGltfList(nodes);
      update(level, x, y, glb);
    }

//    float computedGeometricError = 300f / (maxCompression + 1f - compression);

    String content = level == maxLevel ? "/content/content_glb_" + level + "__" + x + "_" + y + ".glb" : "";
//    String content = "/content/content_glb_" + level + "__" + x + "_" + y + ".glb";

    // Create the tiles
    Tile tile = new Tile(
        boundingVolume,
        0.0f,
        content
    );

    // Create the tileset
    Tileset tileset = new Tileset(
        new Asset("1.1"),
        0.0f,
        new Root(
            tilesetBoundingVolume,
            0.0f,
            "REPLACE",
            new Tile[]{tile}
        ));

    return tileset;
  }

  public List<Building> findBuildings(float xmin, float xmax, float ymin, float ymax, int limit)
      throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         Statement statement = connection.createStatement()) {

      String sql = String.format(FIND_QUERY, ymin * 180 / (float) Math.PI, xmin * 180 / (float) Math.PI,
          ymax * 180 / (float) Math.PI, xmax * 180 / (float) Math.PI, limit);

      logger.debug("Executing query: {}", sql);
//       System.out.println("osm_ways: " + sql);

      List<Building> buildings = new ArrayList<>();

      try (ResultSet resultSet = statement.executeQuery(sql)) {
        while (resultSet.next()) {
          byte[] bytes = resultSet.getBytes(1);
          Geometry geometry = GeometryUtils.deserialize(bytes);

          String building = resultSet.getString(2);
          String height = resultSet.getString(3);
          String buildingLevels = resultSet.getString(4);
          String buildingMinLevels = resultSet.getString(5);
          String buildingColor = resultSet.getString(6);
          String buildingMaterial = resultSet.getString(7);
          String buildingPart = resultSet.getString(8);
          String roofShape = resultSet.getString(9);
          String roofLevels = resultSet.getString(10);
          String roofHeight = resultSet.getString(11);
          String roofColor = resultSet.getString(12);
          String roofMaterial = resultSet.getString(13);
          String roofAngle = resultSet.getString(14);
          String roofDirection = resultSet.getString(15);

          Color finalColor = new Color(1f, 1f, 1f);
          boolean informationFound = true;
          // if no attribute is found, make the building red
          if (height == null && buildingLevels == null && buildingMinLevels == null
              && buildingColor == null && buildingMaterial == null && roofShape == null
              && roofLevels == null && roofHeight == null && roofColor == null
              && roofMaterial == null && roofAngle == null && roofDirection == null) {
            finalColor = new Color(1f, 0f, 0f);
            informationFound = false;
          }

          if (buildingColor != null) {
            try {
              finalColor = ColorUtility.parseName(buildingColor);
            } catch (Exception e) {
//              System.out.println("osm_ways: Error parsing color: " + e); // TODO reput
            }
          }

          float finalHeight = 10;
          if (height != null) {
            finalHeight = Float.parseFloat(height.replaceAll("[^0-9.]", ""));
            if (roofHeight != null) {
              finalHeight -= Float.parseFloat(roofHeight.replaceAll("[^0-9.]", ""));
            }
          } else if (buildingLevels != null || roofLevels != null) {
            finalHeight = 0;
            if (buildingLevels != null) {
              finalHeight += Float.parseFloat(buildingLevels.replaceAll("[^0-9.]", "")) * 3;
            }
            if (roofLevels != null) {
              finalHeight += Float.parseFloat(roofLevels.replaceAll("[^0-9.]", "")) * 3;
            }
          }
          // TODO rise the building in the air instead of making it taller
          if (buildingMinLevels != null) {
            finalHeight += Float.parseFloat(buildingMinLevels.replaceAll("[^0-9.]", "")) * 3;
          }

          // Debug code
          if (finalHeight < 0) {
            System.out.println("Negative height: " + finalHeight);
            finalHeight = 0;
          }

          buildings.add(new Building(
              geometry,
              informationFound,
              finalHeight,
              0,
              finalColor,
              null));
        }
      }
      return buildings;

    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  public void update(int level, long x, long y, byte[] data) throws TileStoreException {
    try (Connection connection = datasource.getConnection()) {
      logger.debug("Executing query: {}", UPSERT_QUERY);
      try (PreparedStatement preparedStatement = connection.prepareStatement(UPSERT_QUERY)) {
        preparedStatement.setLong(1, x);
        preparedStatement.setLong(2, y);
        preparedStatement.setInt(3, level);
        preparedStatement.setBytes(4, data);
        preparedStatement.setBytes(5, data); // for the update part
//        System.out.println("td_tile_gltf: " + preparedStatement.toString());
        preparedStatement.executeUpdate();
//        System.out.println("td_tile_gltf: Updated tile: " + level + "__" + x + "_" + y);
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  public byte[] read(int level, long x, long y) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         PreparedStatement statement = connection.prepareStatement(READ_QUERY)) {
      statement.setLong(1, x);
      statement.setLong(2, y);
      statement.setInt(3, level);
      logger.debug("Executing query: {}", READ_QUERY);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
//          System.out.println("td_tile_gltf: Tile found: " + level + "__" + x + "_" + y);
          return resultSet.getBytes("gltf_binary");
        } else {
//          System.out.println("td_tile_gltf: Tile not found: " + level + "__" + x + "_" + y);
          return null;
        }
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

  private int readBuildingCount(long x, long y, int globalLevel) throws TileStoreException {
    try (Connection connection = datasource.getConnection();
         Statement statement = connection.createStatement()) {
//      int trueLevel = globalLevel - Math.floorDiv(globalLevel, subtreeLevels);
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
//          if (result > 0) {
//            System.out.println("osm_ways: Buildings found: " + result + " at " + globalLevel + "__" + x + "_" + y);
//          }
          return result;
        } else {
          return 0;
        }
      }
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }
}
