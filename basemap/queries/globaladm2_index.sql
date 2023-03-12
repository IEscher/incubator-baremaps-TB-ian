-- Licensed under the Apache License, Version 2.0 (the License); you may not use this file except
-- in compliance with the License. You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software distributed under the License
-- is distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
-- or implied. See the License for the specific language governing permissions and limitations under
-- the License.
CREATE INDEX IF NOT EXISTS globaladm2_index ON globaladm2 USING SPGIST(geom);
CREATE INDEX IF NOT EXISTS globaladm2_z12_index ON globaladm2_z12 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z11_index ON globaladm2_z11 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z10_index ON globaladm2_z10 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z9_index ON globaladm2_z9 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z8_index ON globaladm2_z8 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z7_index ON globaladm2_z7 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z6_index ON globaladm2_z6 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z5_index ON globaladm2_z5 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z4_index ON globaladm2_z4 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z3_index ON globaladm2_z3 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z2_index ON globaladm2_z2 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z1_index ON globaladm2_z1 USING SPGIST (geom);
CREATE INDEX IF NOT EXISTS globaladm2_z0_index ON globaladm2_z0 USING SPGIST (geom);
