
# TB - Ian

## Modifications from `568-experiment-with-3d-tiles` branch

### .run/tdtiles-serve.run.xml

New file to add a new run configuration for the `tdtiles-serve` command.

### baremaps-cli/src/main/java/org/apache/baremaps/cli/Baremaps.java

Adds our package `tdtiles` to the `baremaps` command with PicoCLI.

### baremaps-cli/src/main/java/org/apache/baremaps/cli/tdtiles/Serve.java

Adds a new command `serve` to the `tdtiles` command with PicoCLI.

### baremaps-cli/src/main/java/org/apache/baremaps/cli/tdtiles/TdTiles.java

Adds a new command `tdtiles` to the `baremaps` command with PicoCLI.

### baremaps-core/pom.xml

Adds the `JGLTF` dependency to the `baremaps-core` module.

### baremaps-core/src/main/java/org/apache/baremaps/tdtiles/GltfBuilder.java

Adds the functions to use GLTF, particularly to build houses.

### baremaps-core/src/main/java/org/apache/baremaps/tdtiles/TdTilesStore.java

Parse a (TODO) databse using PostgreSQL to get a list of Buildings.

### baremaps-core/src/main/java/org/apache/baremaps/tdtiles/building/Building.java

Building record containing the geometry and the height of a building.

### baremaps-core/src/main/java/org/apache/baremaps/tdtiles/subtree/Availability.java

Availability record containing a boolean to check if a subtree is available.

### baremaps-core/src/main/java/org/apache/baremaps/tdtiles/subtree/Subtree.java

Subtree record with the current tile's availability,the content's availability and the child subtree's availability.

### baremaps-server/src/main/java/org/apache/baremaps/server/TdTilesResources.java

Creates the webpage serving the 3D Tiles using javax.

### baremaps-server/src/main/resources/tdtiles/favicon.ico

Favicon for the webpage.

### baremaps-server/src/main/resources/tdtiles/index.html

HTML file for the webpage. Downloads Cesium JS and uses it to display the 3D Tiles.

### baremaps-server/src/main/resources/tdtiles/tileset.json

JSON file defining the parameters for displaying the 3D Tiles. Notable parameter : ` "subdivisionScheme" : "QUADTREE"`

### examples/tdtiles/README.md