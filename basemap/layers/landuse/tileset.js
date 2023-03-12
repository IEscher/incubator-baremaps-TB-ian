/**
 Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 in compliance with the License. You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software distributed under the License
 is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 or implied. See the License for the specific language governing permissions and limitations under
 the License.
 **/
export default {
    id: 'landuse',
    queries: [
        {
            minzoom: 3,
            maxzoom: 6,
            sql:
                "SELECT id, tags, geom FROM osm_landuse_z$zoom WHERE tags ->> 'landuse' IN ('farmland', 'forest', 'meadow')",
        },
        {
            minzoom: 6,
            maxzoom: 12,
            sql:
                "SELECT id, tags, geom FROM osm_landuse_z$zoom WHERE tags ->> 'landuse' IN ('farmland', 'forest', 'meadow', 'residential', 'vineyard')",
        },
        {
            minzoom: 12,
            maxzoom: 20,
            sql: "SELECT id, tags, geom FROM osm_ways_z$zoom WHERE tags ? 'landuse'"
        },
        {
            minzoom: 12,
            maxzoom: 20,
            sql: "SELECT id, tags, geom FROM osm_relations_z$zoom WHERE tags ? 'landuse'"
        }
    ],
}
