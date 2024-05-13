
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


## Cahier de route

### 12.05.2024

- Création du système de couleurs `baremaps-core/src/main/java/org/apache/baremaps/tdtiles/utils`
- Création des records Roof et Color, modif de Building et Création de ColorUtility pour parse les noms des couleures en value rgb
- Modification de la requête SQL dans `TdTilesStore.java` pour récupérer tous les types de bâtiments, toutes leurs parties, leur toit et les attributs de leur toit

#### Problèmes aperçus

- les requètes SQL ne prennent que les premiers bâtiements dans les quadtrees donc on se retrouve avec des trous entre les quadtrees car les bâtiments ne sont pas séléctionnés en fonction de leur distance

### 13.05.2024

- Implémentation de la hauteur des bâtiments dans `TdTilesStore.java` selon la documentation de 3D Tiles
- Changement de "triangulator" pour le calcul des géométries des bâtiments permettant de render les géométries concaves
- Fix du "winding order" pour les triangles du nouveau "triangulator", leurs faces étaient à l'envers. Pas de changement au niveau des normales n'a ensuite été nécessaire

#### Problèmes aperçus

- Un problème existe peut-être dans la disposition des triangles des parrois
