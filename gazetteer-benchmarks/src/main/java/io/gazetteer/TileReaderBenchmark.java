package io.gazetteer;

import io.gazetteer.osm.database.PostgisHelper;
import io.gazetteer.tiles.Tile;
import io.gazetteer.tiles.TileReader;
import io.gazetteer.tiles.TileWriter;
import io.gazetteer.tiles.config.Config;
import io.gazetteer.tiles.file.FileTileStore;
import io.gazetteer.tiles.postgis.BasicTileReader;
import io.gazetteer.tiles.postgis.WithTileReader;
import io.gazetteer.tiles.util.TileUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
public class TileReaderBenchmark {

  private Config config;
  private PoolingDataSource datasource;
  private Path output;
  private TileReader reader;
  private TileWriter writer;

  @Setup(Level.Invocation)
  public void prepare() throws IOException {
    config = Config.load(new FileInputStream(new File("./config/config.yaml")));
    datasource = PostgisHelper.poolingDataSource("jdbc:postgresql://localhost:5432/gazetteer?allowMultiQueries=true&user=gazetteer&password=gazetteer");
    writer = new FileTileStore(output = Files.createTempDirectory("tiles"));
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @Warmup(iterations = 1)
  @Measurement(iterations = 2)
  public void basic() throws SQLException, ParseException {
    reader = new BasicTileReader(datasource, config);
    execute();
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @Warmup(iterations = 1)
  @Measurement(iterations = 2)
  public void with() throws SQLException, ParseException {
    reader = new WithTileReader(datasource, config);
    execute();
  }

  public void execute() throws SQLException, ParseException {
    try (Connection connection = datasource.getConnection()) {
      Geometry geometry = TileUtil.bbox(connection);
      Stream<Tile> coords = TileUtil.getTiles(geometry, 14, 14);
      coords.forEach(xyz -> {
        try {
          byte[] tile = reader.read(xyz);
          writer.write(xyz, tile);
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }
  }


}