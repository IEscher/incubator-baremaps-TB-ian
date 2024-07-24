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
import org.apache.baremaps.tdtiles.TdSubtreeStore;
import org.apache.baremaps.tdtiles.TdTilesStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TdTilesResources {

  // 0 = no compression, 1 = low compression, 2 = high compression + only roof, 3 = only important
  // buildings
  // If changing this value, changes must also be made in gltf.sql
  private static final int MAX_COMPRESSION = 3;

  private static final int MIN_LEVEL = 16;
  private static final int MAX_LEVEL = 19; // Cannot be over Long.BYTES * 8 / 2 because it uses 2
                                           // bits per level
  // todo mettre le calcul correcte dans le rapport (level - Math.floorDiv(level, subtreeLevels))

  // Levels to which the compression is increased
  private static final int[] COMPRESSION_LEVELS = {MAX_LEVEL - 1, MAX_LEVEL - 2, MAX_LEVEL - 3};

  // Subtree levels
  // See: https://github.com/CesiumGS/3d-tiles/issues/576 for subtree division
  private static final int AVAILABLE_LEVELS = MAX_LEVEL; // AVAILABLE_LEVELS + 1 should be a
                                                         // multiple of SUBTREE_LEVELS
  private static final int SUBTREE_LEVELS = 4;

  private static final boolean RELOAD_SUBTREES = false;
  private static final boolean RELOAD_TILES = true;
  private static final boolean GENERATE_ALL_SUBTREES = false;


  // private static final int MIN_LEVEL = 0;
  // private static final int MAX_LEVEL = 3; // Cannot be over Integer.BYTES * 8
  //
  // // Levels to which the compression is increased
  // private static final int[] COMPRESSION_LEVELS = {0, 1, 2};
  //
  // // Subtree levels
  // // See: https://github.com/CesiumGS/3d-tiles/issues/576 for subtree division
  // private static final int AVAILABLE_LEVELS = MAX_LEVEL; // AVAILABLE_LEVELS + 1 should be a
  // multiple of SUBTREE_LEVELS
  // private static final int SUBTREE_LEVELS = 4;



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
    this.tdTilesStore =
        new TdTilesStore(dataSource, MAX_COMPRESSION, COMPRESSION_LEVELS, MIN_LEVEL, MAX_LEVEL, RELOAD_TILES);
    this.tdSubtreeStore = new TdSubtreeStore(dataSource, MIN_LEVEL, MAX_LEVEL,
        SUBTREE_LEVELS, RANK_AMOUNT, RELOAD_SUBTREES, GENERATE_ALL_SUBTREES);
  }

  @Get("regex:^/subtrees/(?<level>[0-9]+).(?<x>[0-9]+).(?<y>[0-9]+).subtree")
  public HttpResponse getSubtree(@Param("level") int level, @Param("x") long x,
      @Param("y") long y) {
    // See:
    // https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/README.adoc#subtrees
    try {
      return HttpResponse.of(BINARY_HEADERS, HttpData.wrap(tdSubtreeStore.getSubtree(level, x, y)));
    } catch (Exception e) {
      logger.error("Error getting subtree", e);
      System.err.println("Error getting subtree: " + e);
      return HttpResponse.of(BINARY_HEADERS, HttpData.empty());
    }
  }

  @Get("regex:^/content/content_glb_(?<level>[0-9]+)__(?<x>[0-9]+)_(?<y>[0-9]+).glb")
  public HttpResponse getGlb(@Param("level") int level, @Param("x") long x, @Param("y") long y)
      throws Exception {
    byte[] glb = tdTilesStore.getGlb(level, x, y);
    if (glb != null) {
      // System.out.println("--------------------------------- Giving building: " + level + "__" + x
      // + "_" + y);
      return HttpResponse.of(BINARY_HEADERS, HttpData.wrap(glb));
    } else {
      System.err.println("Giving empty glb for level: " + level + "__" + x + "_" + y);
      return HttpResponse.of(BINARY_HEADERS, HttpData.empty());
    }
  }
}
