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


import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import javax.sql.DataSource;
import org.apache.baremaps.tdtiles.building.Building;
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
  private static final String QUERY =
      "select st_asbinary(geom), " + // 1
          "tags -> 'building', " + // 2
          "tags -> 'building:height', " + // 3
          "tags -> 'height', " + // 4
          "tags -> 'building:levels', " + // 5
          "tags -> 'building:min_level', " + // 6
          "tags -> 'building:colour', " + // 7
          "tags -> 'building:material', " + // 8
          "tags -> 'building:part', " + // 9
          "tags -> 'roof:shape', " + // 10
          "tags -> 'roof:levels', " + // 11
          "tags -> 'roof:height', " + // 12
          "tags -> 'roof:color', " + // 13
          "tags -> 'roof:material'," + // 14
          "tags -> 'roof:angle', " + // 15
          "tags -> 'roof:direction', " + // 16
          "tags -> 'amenity' " + // 17
          "from osm_ways where (tags ? 'building' or tags ? 'building:part' or tags ? 'amenity') and "
          +
          "st_intersects(geom, st_makeenvelope(%1$s, %2$s, %3$s, %4$s, 4326)) LIMIT %5$s";


  private final DataSource datasource;

  public TdTilesStore(DataSource datasource) {
    this.datasource = datasource;
  }

  public List<Building> read(float xmin, float xmax, float ymin, float ymax, int limit)
      throws TileStoreException {
    try (Connection connection = datasource.getConnection();
        Statement statement = connection.createStatement()) {

      String sql = String.format(QUERY, ymin * 180 / (float) Math.PI, xmin * 180 / (float) Math.PI,
          ymax * 180 / (float) Math.PI, xmax * 180 / (float) Math.PI, limit);

      logger.debug("Executing query: {}", sql);
      // System.out.println(sql);

      List<Building> buildings = new ArrayList<>();

      try (ResultSet resultSet = statement.executeQuery(sql)) {
        while (resultSet.next()) {
          byte[] bytes = resultSet.getBytes(1);
          Geometry geometry = GeometryUtils.deserialize(bytes);

          String building = resultSet.getString(2);
          String buildingHeight = resultSet.getString(3);
          String height = resultSet.getString(4);
          String buildingLevels = resultSet.getString(5);;
          String buildingMinLevels = resultSet.getString(6);
          String buildingColor = resultSet.getString(7);
          String buildingMaterial = resultSet.getString(8);
          String buildingPart = resultSet.getString(9);
          String roofShape = resultSet.getString(10);
          String roofLevels = resultSet.getString(11);
          String roofHeight = resultSet.getString(12);
          String roofColor = resultSet.getString(13);
          String roofMaterial = resultSet.getString(14);
          String roofAngle = resultSet.getString(15);
          String roofDirection = resultSet.getString(16);
          String amenity = resultSet.getString(17);

          Color finalColor = new Color(1f, 0f, 0f);
          if (buildingColor != null) {
            try {
              finalColor = ColorUtility.parseName(buildingColor);
            } catch (Exception e) {
              System.out.println("Error parsing color: " + e);
            }
          }

          float finalHeight = 10;
          // if (buildingHeight != null) {
          // finalHeight = Float.parseFloat(buildingHeight.replaceAll("[^0-9]", ""));
          // } else if (height != null) {
          // finalHeight = Float.parseFloat(height.replaceAll("[^0-9]", ""));
          // } else if (buildingLevels != null || roofLevels != null) {
          // finalHeight = 0;
          // if (buildingLevels != null) {
          // finalHeight += Float.parseFloat(buildingLevels.replaceAll("[^0-9]", "")) * 3;
          // }
          // if (roofLevels != null) {
          // finalHeight += Float.parseFloat(roofLevels.replaceAll("[^0-9]", "")) * 3;
          // }
          // }

          buildings.add(new Building(geometry, finalHeight, 0, finalColor, null));
        }
      }
      return buildings;
    } catch (SQLException e) {
      throw new TileStoreException(e);
    }
  }

}
