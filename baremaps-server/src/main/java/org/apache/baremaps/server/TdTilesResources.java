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
import de.javagl.jgltf.model.NodeModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.apache.baremaps.tdtiles.GltfBuilder;
import org.apache.baremaps.tdtiles.TdTilesStore;
import org.apache.baremaps.tdtiles.building.Building;
import org.apache.baremaps.tdtiles.subtree.Availability;
import org.apache.baremaps.tdtiles.subtree.Subtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdTilesResources {

  private static final Logger logger = LoggerFactory.getLogger(TdTilesStore.class);

  private static final int minLevel = 14;
  private static final int maxLevel = 18;

  private static final ResponseHeaders GLB_HEADERS = ResponseHeaders.builder(200)
      .add(CONTENT_TYPE, BINARY)
      .add(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
      .build();

  private static final ResponseHeaders JSON_HEADERS = ResponseHeaders.builder(200)
      .add(CONTENT_TYPE, APPLICATION_JSON)
      .add(ACCESS_CONTROL_ALLOW_ORIGIN, "*")
      .build();

  private final TdTilesStore tdTilesStore;

  public TdTilesResources(DataSource dataSource) {
    this.tdTilesStore = new TdTilesStore(dataSource);
  }

  @Get("regex:^/subtrees/(?<level>[0-9]+).(?<x>[0-9]+).(?<y>[0-9]+).json")
  public HttpResponse getSubtree(@Param("level") int level, @Param("x") int x, @Param("y") int y) {
    // https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/README.adoc#subtrees
    if (level >= maxLevel) {
      return HttpResponse.ofJson(JSON_HEADERS,
          new Subtree(new Availability(false), new Availability(true), new Availability(false)));
    }
    return HttpResponse.ofJson(JSON_HEADERS,
        new Subtree(new Availability(true), new Availability(true), new Availability(true)));
  }

  @Get("regex:^/content/content_(?<level>[0-9]+)__(?<x>[0-9]+)_(?<y>[0-9]+).glb")
  public HttpResponse getContent(@Param("level") int level, @Param("x") int x, @Param("y") int y)
      throws Exception {
    if (level < minLevel) {
      return HttpResponse.of(GLB_HEADERS, HttpData.wrap(GltfBuilder.createGltf(new ArrayList<>())));
    }

    float[] coords = xyzToLatLonRadians(x, y, level);
    int limit = 1000;
    float range = 0.00001f;
    List<Building> buildings;
    int levelDelta = maxLevel - minLevel;
    int compression = (maxLevel - level) * GltfBuilder.MAX_COMPRESSION / levelDelta;
    if (level != maxLevel) {
      buildings = tdTilesStore.read(coords[0], coords[1], coords[2], coords[3], limit);
    } else {
      buildings = tdTilesStore.read(coords[0] - range, coords[1] + range, coords[2] - range,
          coords[3] + range, limit);
    }

    List<NodeModel> nodes = createNodes(buildings, compression);

    return HttpResponse.of(GLB_HEADERS, HttpData.wrap(GltfBuilder.createGltf(nodes)));
  }

  /**
   * Create nodes from buildings in parallel.
   * 
   * @param buildings
   * @param level
   * @return
   * @throws Exception
   */
  private static List<NodeModel> createNodes(List<Building> buildings, int level) throws Exception {
    int numCores = Runtime.getRuntime().availableProcessors();
    ExecutorService EXEC = Executors.newFixedThreadPool(numCores);
    List<Callable<NodeModel>> tasks = new ArrayList<Callable<NodeModel>>();
    for (final Building building : buildings) {
      Callable<NodeModel> c = new Callable<NodeModel>() {
        @Override
        public NodeModel call() throws Exception {
          return GltfBuilder.createNode(building, level);
        }
      };
      tasks.add(c);
    }

    List<Future<NodeModel>> results = EXEC.invokeAll(tasks);
    List<NodeModel> nodes = new ArrayList<NodeModel>();

    for (Future<NodeModel> f : results) {
      try {
        nodes.add(f.get());
      } catch (Exception e) {
        logger.error("Error creating node", e);
      }
    }
    return nodes;
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
