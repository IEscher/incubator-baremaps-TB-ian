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

package org.apache.baremaps.tdtiles.utils;

public class MortonIndexes {
  /**
   * See:
   * https://github.com/CesiumGS/3d-tiles/blob/main/specification/ImplicitTiling/AVAILABILITY.adoc
   *
   * @param x
   * @param y
   * @param level
   * @return The morton index of the two coordinates at the given level
   */
  public static long interleaveBits(long x, long y, int level) {
    // int trueLevel = level - Math.floorDiv(level, subtreeLevels);
    long result = 0;
    int length = (int) Math.pow(2, level);
    for (int i = 0; i < length; i++) {
      result |= (x & (1L << i)) << i | (y & (1L << i)) << (i + 1);
    }
    return result;
  }

  public static long[] mortonIndexToXY(long mortonIndex, int level) {
    // int trueLevel = level - Math.floorDiv(level, subtreeLevels);
    long[] result = new long[2];
    int length = (int) Math.pow(2, level);
    long x = 0;
    long y = 0;
    for (int i = 0; i < length; i++) {
      x |= (mortonIndex & (1L << (2 * i))) >> i;
      y |= (mortonIndex & (1L << (2 * i + 1))) >> (i + 1);
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
