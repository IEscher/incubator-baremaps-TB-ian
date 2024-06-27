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

package org.apache.baremaps.server;

import static com.google.common.net.HttpHeaders.*;
import static io.netty.handler.codec.http.HttpHeaders.Values.APPLICATION_JSON;
import static io.netty.handler.codec.http.HttpHeaders.Values.BINARY;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;

import javax.sql.DataSource;

import de.javagl.jgltf.model.NodeModel;
import org.apache.baremaps.tdtiles.GltfBuilder;
import org.apache.baremaps.tdtiles.TdSubtreeStore;
import org.apache.baremaps.tdtiles.TdTilesStore;
import org.apache.baremaps.tdtiles.building.Building;
//import org.apache.baremaps.tdtiles.subtree.Subtree;
import org.apache.baremaps.tdtiles.tileset.*;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

public class TdTilesResources {

  // 0 = no compression, 1 = low compression, 2 = high compression + only roof, 3 = only important buildings
  // If changing this value, changes must also be made in TdTiledStore.java, TdSubtreeStore.java, and gltf.sql
  private static final int MAX_COMPRESSION = 3;

  private static final int MIN_LEVEL = 0;
  private static final int MAX_LEVEL = 3; // Cannot be over Integer.BYTES * 8

  // Levels to which the compression is increased
  private static final int[] COMPRESSION_LEVELS = {2, 1, 0};

  // Subtree levels
  // See: https://github.com/CesiumGS/3d-tiles/issues/576 for subtree division
  private static final int AVAILABLE_LEVELS = MAX_LEVEL; // AVAILABLE_LEVELS + 1 should be a multiple of SUBTREE_LEVELS
  private static final int SUBTREE_LEVELS = 4;
  private static final int RANK_AMOUNT = (AVAILABLE_LEVELS + 1) / SUBTREE_LEVELS;

  private static final Logger logger = LoggerFactory.getLogger(TdTilesStore.class);

  private static final ResponseHeaders BINARY_HEADERS = ResponseHeaders.builder(200)
      .add(CONTENT_TYPE, BINARY)
      .add(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
      .build();

  private static final ResponseHeaders JSON_HEADERS = ResponseHeaders.builder(200)
      .add(CONTENT_TYPE, APPLICATION_JSON)
      .add(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
      .build();

  private final TdTilesStore tdTilesStore;
  private final TdSubtreeStore tdSubtreeStore;

  public TdTilesResources(DataSource dataSource) {
    this.tdTilesStore = new TdTilesStore(dataSource, MAX_COMPRESSION);
    this.tdSubtreeStore = new TdSubtreeStore(dataSource, MAX_COMPRESSION, MIN_LEVEL, MAX_LEVEL, COMPRESSION_LEVELS,
        AVAILABLE_LEVELS, SUBTREE_LEVELS, RANK_AMOUNT);
  }

  @Get("regex:^/subtrees/(?<level>[0-9]+).(?<x>[0-9]+).(?<y>[0-9]+).subtree")
  public HttpResponse getSubtree(@Param("level") int level, @Param("x") int x, @Param("y") int y) {
    // See: https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/README.adoc#subtrees
    try {
      return HttpResponse.of(BINARY_HEADERS, HttpData.wrap(tdSubtreeStore.getSubtree(level, x, y)));
    } catch (Exception e) {
      logger.error("Error getting subtree", e);
      System.err.println("Error getting subtree: " + e);
      return HttpResponse.of(BINARY_HEADERS, HttpData.empty());
    }
  }

  @Get("regex:^/content/content_(?<level>[0-9]+)__(?<x>[0-9]+)_(?<y>[0-9]+).json")
  public HttpResponse getTileset(@Param("level") int level, @Param("x") int x, @Param("y") int y)
      throws Exception {
    if (level < MIN_LEVEL) {
//      Tile example = new Tile(
//          new BoundingVolume(new Float[]{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}),
//          "/content/content_building_id_1.glb",
//          0
//      );
      Tileset tileset = new Tileset(
          new Asset("1.1"),
          100f,
          new Root(
              new BoundingVolume(new Float[]{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}),
              0f,
              "ADD",
              new Tile[0]
//            new Tile[]{example}
          ));
      return HttpResponse.ofJson(JSON_HEADERS, tileset);
    }

    // Retrieve the gltf in the database if it exists
    byte[] tileExists = tdTilesStore.read(level, x, y);

    if (tileExists == null) {
      // Find the unprocessed buildings in the tile
      System.out.println("Creating tile at level: " + level + ", x: " + x + ", y: " + y);
      float[] coords = xyzToLatLonRadians(x, y, level);
      int limit = 5000;
      List<Building> buildings;
      int levelDelta = MAX_LEVEL - MIN_LEVEL;
      int compression = (MAX_LEVEL - level) * MAX_COMPRESSION / levelDelta;
      buildings = tdTilesStore.findBuildings(coords[0], coords[1], coords[2], coords[3], limit);
      List<NodeModel> nodes = new LinkedList<>();
      for (Building building : buildings) {
        nodes.add(GltfBuilder.createNode(building, compression));
      }

      // Update the database with the tile
      byte[] glb = GltfBuilder.createGltfList(nodes);
      tdTilesStore.update(level, x, y, glb);
    }

    // Create the tiles
    Tile tile = new Tile(
          new BoundingVolume(new Float[]{-1.2419052957251926f,
              0.7395016240301894f,
              -1.2f,
              0.7396563300150859f,
              0f,
              20.4f}),
          "/content/content_glb_" + level + "__" + x + "_" + y + ".glb"
      );

    // Create the tileset
    Tileset tileset = new Tileset(
        new Asset("1.1"),
        100f,
        new Root(
            new BoundingVolume(new Float[]{-1.2419052957251926f,
                0.7f,
                -1.2415404f,
                0.7f,
                0f,
                20.0f}),
            100f,
            "REPLACE",
            new Tile[]{tile}
        ));

    return HttpResponse.ofJson(JSON_HEADERS, tileset);

  }

  @Get("regex:^/content/content_glb_(?<level>[0-9]+)__(?<x>[0-9]+)_(?<y>[0-9]+).glb")
  public HttpResponse getGlb(@Param("level") int level, @Param("x") int x, @Param("y") int y) throws Exception {
    byte[] glb = tdTilesStore.read(level, x, y);
    if (glb != null) {
      System.out.println("--------------------------------- Giving building: " + level + "__" + x + "_" + y);
      return HttpResponse.of(BINARY_HEADERS, HttpData.wrap(glb));
    } else {
      System.err.println("Giving empty glb for level: " + level + ", x: " + x + ", y: " + y);
      return HttpResponse.of(BINARY_HEADERS, HttpData.empty());
    }
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
