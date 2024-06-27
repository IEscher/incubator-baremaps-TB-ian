package org.apache.baremaps.tdtiles.tileset;

public record Root(BoundingVolume boundingVolume, Float geometricError, String refine, Tile[] contents) {
}
