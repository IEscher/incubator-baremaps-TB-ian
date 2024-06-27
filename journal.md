# Cahier de route

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

### Probablement le 17.05.2024

- Profiling de l'application -> "writeBinary" de Gltf prend énormément de temps
- Multithreading pour le calcul des géométries des bâtiments ("createNode" de GltfBuilder)

### 19-20.05.2024

- Optimisation de "levels" du Implicit Tiling pour avoir une quantité équilibrée
- Recherche d'une solution pour l'utilisation de LODs (et geomertic error) mais ne semble pas être compatible avec le implicit tiling
- Création d'un système de compression des géométries des bâtiments en fonction de leur "levels" pour réduire la taille du fichier GLTF

### 17.06.2024

- Recherche sur le caching et analyse d'autres modules de Baremaps à ce sujet

### 18.06.2024

- Début d'un système de cache par building stocké dans la database postgresql

### 19.06.2024

- Recherche sur les méthodes d'envoi des données gltf. Avant, les données étaient envoyées en une seule fois par tiles.
- Étude sur la génération de tilesets et de tiles

### 20.06.2024

- Système de génération de tilesets et de tiles fonctionnel avec lecture / écriture dans la base de donnée
- Bug: Cesium ne lit pas les uri dans les contents des tiles, mais ça marchait avec des tiles/tileset hardcodé

### 21.06.2024

- Recherche sur l'Implicit Tiling

### 22.06.2024

- Planification de l'implicit tiling et début de son écriture