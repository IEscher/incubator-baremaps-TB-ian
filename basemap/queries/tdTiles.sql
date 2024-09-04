-- ALTER TABLE osm_ways
--     ADD gltf_binary_c0 bytea,
--     ADD gltf_binary_c1 bytea,
--     ADD gltf_binary_c2 bytea,
--     ADD gltf_binary_c3 bytea;

DROP TABLE IF EXISTS td_subtrees;
CREATE TABLE td_subtrees
(
    morton_index bigint,
    level        integer,
    binary_file  bytea,
    UNIQUE (morton_index, level)
);

DROP TABLE IF EXISTS td_tile_gltf;
CREATE TABLE td_tile_gltf
(
    x           bigint,
    y           bigint,
    level       integer,
    gltf_binary bytea,
    UNIQUE (x, y, level)
);

DROP TABLE IF EXISTS td_tile_buildings_in_tiles;
CREATE TABLE td_tile_buildings_in_tiles
(
    id          bigint,
    compression integer,
    UNIQUE (id, compression)
);