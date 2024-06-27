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

import org.apache.baremaps.tdtiles.GltfBuilder;
import org.apache.baremaps.tdtiles.TdSubtreeStore;
import org.apache.baremaps.tdtiles.TdTilesStore;
import org.apache.baremaps.tdtiles.building.Building;
import org.apache.baremaps.tdtiles.subtree.TileAvailability;
import org.apache.baremaps.tdtiles.subtree.Subtree;
import org.apache.baremaps.tdtiles.tileset.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

public class TdTilesResources {

  private static final Logger logger = LoggerFactory.getLogger(TdTilesStore.class);

  // 0 = no compression, 1 = low compression, 2 = high compression + only roof, 3 = only important buildings
  // If changing this value, changes must also be made in TdTiledStore.java, TdSubtreeStore.java, and tdTiles.sql
  private static final int MAX_COMPRESSION = 3;

  private static final int MIN_LEVEL = 14;
  private static final int MAX_LEVEL = 17;

  private static final int[] COMPRESSION_LEVELS = {17, 16, 15};

  // Subtree levels
  // See: https://github.com/CesiumGS/3d-tiles/issues/576 for subtree division


  private static final ResponseHeaders GLB_HEADERS = ResponseHeaders.builder(200)
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
    this.tdSubtreeStore = new TdSubtreeStore(dataSource);
  }

  @Get("regex:^/subtrees/(?<level>[0-9]+).(?<x>[0-9]+).(?<y>[0-9]+).json")
  public HttpResponse getSubtree(@Param("level") int level, @Param("x") long x, @Param("y") long y) {
    // https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/README.adoc#subtrees
//    BitSet availableBitset = new BitSet(5);
//    availableBitset.set(0, 5);
//    BitSet unavailableBitset = new BitSet(5);
//    BitSet rootOnlyBitset = new BitSet(5);
//    rootOnlyBitset.set(0);
//    BitSet childOnlyBitset = new BitSet(5);
//    childOnlyBitset.set(1, 5);
//
//    Availability available = new Availability(availableBitset);
//    Availability unavailable = new Availability(unavailableBitset);
//    Availability rootOnly = new Availability(rootOnlyBitset);
//    Availability childOnly = new Availability(childOnlyBitset);

//    BitSet availableBitset = new BitSet(1);
//    availableBitset.set(0);
//    BitSet unavailableBitset = new BitSet(1);
//    BitSet childrenAvailableBitset = new BitSet(4);
//    childrenAvailableBitset.set(0, 4);
//    BitSet childrenUnavailableBitset = new BitSet(4);
//
//    TileAvailability available = new TileAvailability(availableBitset);
//    TileAvailability unavailable = new TileAvailability(unavailableBitset);
//    TileAvailability childrenAvailable = new TileAvailability(childrenAvailableBitset);
//    TileAvailability childrenUnavailable = new TileAvailability(childrenUnavailableBitset);

//    Availability available = new Availability(1);
//    Availability unavailable = new Availability(0);

    if (level >= MAX_LEVEL) {
      return HttpResponse.ofJson(JSON_HEADERS,
          new Subtree(unavailable, available, childrenUnavailable));
    }
    return HttpResponse.ofJson(JSON_HEADERS,
        new Subtree(available, available, childrenAvailable));
  }

  @Get("regex:^/content/content_(?<level>[0-9]+)__(?<x>[0-9]+)_(?<y>[0-9]+).json")
  public HttpResponse getTileset(@Param("level") int level, @Param("x") long x, @Param("y") long y)
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

//    StringBuilder sb = new StringBuilder();
//    sb.append("/content/content_building_id_");
//    sb.append(x + y + level);
//    sb.append(".glb");

//    Tile tile1 = new Tile(
//        "/content/content_building_id_1.glb",
//        new BoundingVolume(new Float[]{-1.2419052957251926f,
//            0.7395016240301894f,
//            -1.2f,
//            0.7396563300150859f,
//            0f,
//            20.4f}),
//        0
//        );
//
//    Tile tile2 = new Tile(
//        "/content/content_building_id_2.glb",
//        new BoundingVolume(new Float[]{-1.2419052957251926f,
//            0.7395016240301894f,
//            -1.2f,
//            0.7396563300150859f,
//            0f,
//            20.4f}),
//        1
//    );
//
//    Tileset tileset = new Tileset(new Asset("1.1"), 100f, new Root(
//        new BoundingVolume(new Float[]{-1.2419052957251926f,
//            0.7f,
//            -1.2415404f,
//            0.7f,
//            0f,
//            20.0f}),
//        100f,
//        "ADD",
//        new Tile[]{tile1, tile2}
//    ));
//
//    return HttpResponse.ofJson(JSON_HEADERS, tileset);

    // Find the unprocessed buildings in the tile
    float[] coords = xyzToLatLonRadians(x, y, level);
    int limit = 5000;
    List<Building> buildings;
    int levelDelta = MAX_LEVEL - MIN_LEVEL;
    int compression = (MAX_LEVEL - level) * MAX_COMPRESSION / levelDelta;
    buildings = tdTilesStore.findBuildings(coords[0], coords[1], coords[2], coords[3], limit);
    List<Building> unprocessedBuildings = new LinkedList<>();

    for (final Building building : buildings) {
      if (building.compression().get(compression) == null) {
        unprocessedBuildings.add(building);
        System.out.println("Building added to processing: " + building.id());
      }
    }

//    List<NodeModel> nodes = new ArrayList<NodeModel>();
    // Process the buildings not yet processed
    for (final Building building : unprocessedBuildings) {
//      nodes.add(GltfBuilder.createNode(building, compression));
      byte[] gltf = GltfBuilder.createGltf(GltfBuilder.createNode(building, compression));
    }

    // return HttpResponse.of(GLB_HEADERS, HttpData.wrap(GltfBuilder.createGltf(nodes)));

    // Create the tiles
    Tile[] tiles = new Tile[buildings.size()];
    int i = 0;
    for (final Building building : buildings) {
      tiles[i] = new Tile(
          new BoundingVolume(new Float[]{-1.2419052957251926f,
            0.7395016240301894f,
            -1.2f,
            0.7396563300150859f,
            0f,
            20.4f}),
          "/content/content_building_id_" + building.id() + "_" + compression + ".glb"
      );
      i++;
    }
    if (tiles.length != 0) {
      System.out.println("Tileset created with " + tiles.length + " tiles.\tCoordinates: " + level + "__" + x + "_" + y);
    }

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
          tiles
    ));

    return HttpResponse.ofJson(JSON_HEADERS, tileset);

  }

  @Get("regex:^/content/content_building_id_(?<id>[0-9]+)_(?<compression>[0-9]+).glb")
  public HttpResponse getBuildingById(@Param("id") long id, @Param("compression") int compression) throws Exception {
    System.out.println("--------------------------------------------------------Building ID: " + id);
    byte[] glb = tdTilesStore.read(id, compression);
    return HttpResponse.of(GLB_HEADERS, HttpData.wrap(glb));
  }

  /**
   * Convert XYZ tile coordinates to lat/lon in radians.
   *
   * @param x
   * @param y
   * @param z
   * @return
   */
  public static float[] xyzToLatLonRadians(long x, long y, int z) {
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
