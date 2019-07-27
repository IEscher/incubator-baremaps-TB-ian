// The following code largely comes from PgBulkInsert.
// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license.

package io.gazetteer.common.postgis;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.postgresql.copy.PGCopyOutputStream;

public class CopyWriter implements AutoCloseable {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private static final byte IPV4 = 2;
  private static final byte IPV4_MASK = 32;
  private static final byte IPV4_IS_CIDR = 0;

  private static final byte IPV6 = 3;
  private static final int IPV6_MASK = 128;
  private static final byte IPV6_IS_CIDR = 0;

  private final DataOutputStream data;

  public CopyWriter(PGCopyOutputStream data) {
    this.data = new DataOutputStream(new BufferedOutputStream(data, 65536));
  }

  public void writeHeader() throws IOException {
    // 11 bytes required header
    data.writeBytes("PGCOPY\n\377\r\n\0");
    // 32 bit integer indicating no OID
    data.writeInt(0);
    // 32 bit header extension area length
    data.writeInt(0);
  }

  public void startRow(int columns) throws IOException {
    data.writeShort(columns);
  }

  public void writeNull() throws IOException {
    data.writeInt(-1);
  }

  public void writeString(String value) throws IOException {
    nullableWriter(CopyWriter::stringWriter).write(data, value);
  }

  public void writeStringList(List<String> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Text, CopyWriter::stringWriter)).write(data, value);
  }

  public void writeBoolean(Boolean value) throws IOException {
    nullableWriter(CopyWriter::booleanWriter).write(data, value);
  }

  public void writeBooleanList(List<Boolean> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Boolean, CopyWriter::booleanWriter)).write(data, value);
  }

  public void writeByte(Byte value) throws IOException {
    nullableWriter(CopyWriter::byteWriter).write(data, value);
  }

  public void writeByteArray(byte[] value) throws IOException {
    nullableWriter(CopyWriter::byteArrayWriter).write(data, value);
  }

  public void writeShort(Short value) throws IOException {
    nullableWriter(CopyWriter::shortWriter).write(data, value);
  }

  public void writeShortList(List<Short> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Int4, CopyWriter::shortWriter)).write(data, value);
  }

  public void writeInteger(Integer value) throws IOException {
    nullableWriter(CopyWriter::integerWriter).write(data, value);
  }

  public void writeIntegerList(List<Integer> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Int4, CopyWriter::integerWriter)).write(data, value);
  }

  public void writeLong(Long value) throws IOException {
    nullableWriter(CopyWriter::longWriter).write(data, value);
  }

  public void writeLongList(List<Long> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Int8, CopyWriter::longWriter)).write(data, value);
  }

  public void writeFloat(Float value) throws IOException {
    nullableWriter(CopyWriter::floatWriter).write(data, value);
  }

  public void writeFloatList(List<Float> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Int8, CopyWriter::floatWriter)).write(data, value);
  }

  public void writeDouble(Double value) throws IOException {
    nullableWriter(CopyWriter::doubleWriter).write(data, value);
  }

  public void writeDoubleArray(List<Double> value) throws IOException {
    nullableWriter(collectionWriter(ObjectIdentifier.Int8, CopyWriter::doubleWriter)).write(data, value);
  }

  public void writeLocalDate(LocalDate value) throws IOException {
    nullableWriter(CopyWriter::localDateWriter).write(data, value);
  }

  public void writeLocalDateTime(LocalDateTime value) throws IOException {
    nullableWriter(CopyWriter::localDateTimeWriter).write(data, value);
  }

  public void writeInet4Adress(Inet4Address value) throws IOException {
    nullableWriter(CopyWriter::inet4AdressWriter).write(data, value);
  }

  public void writeInet6Adress(Inet6Address value) throws IOException {
    nullableWriter(CopyWriter::inet6AdressWriter).write(data, value);
  }

  public void writeHstore(Map<String, String> value) throws IOException {
    nullableWriter(CopyWriter::hstoreWriter).write(data, value);
  }

  public void writeGeometry(Geometry value) throws IOException {
    nullableWriter(CopyWriter::byteArrayWriter).write(data, GeometryUtils.toWKB(value));
  }

  @Override
  public void close() throws Exception {
    data.writeShort(-1);
    data.flush();
    data.close();
  }

  private static <T> ValueWriter<T> nullableWriter(ValueWriter<T> writer) {
    return (data, value) -> {
      if (value == null) {
        data.writeInt(-1);
        return;
      }
      writer.write(data, value);
    };
  }

  private static void booleanWriter(DataOutputStream data, Boolean value) throws IOException {
    data.writeInt(1);
    if (value) {
      data.writeByte(1);
    } else {
      data.writeByte(0);
    }
  }

  private static void byteWriter(DataOutputStream data, Byte value) throws IOException {
    data.writeInt(1);
    data.writeShort(value.byteValue());
  }

  private static void byteArrayWriter(DataOutputStream data, byte[] value) throws IOException {
    data.writeInt(value.length);
    data.write(value, 0, value.length);
  }

  private static void shortWriter(DataOutputStream data, Short value) throws IOException {
    data.writeInt(2);
    data.writeShort(value.shortValue());
  }

  private static void integerWriter(DataOutputStream data, Integer value) throws IOException {
    data.writeInt(4);
    data.writeInt(value.intValue());
  }

  private static void floatWriter(DataOutputStream data, Float value) throws IOException {
    data.writeInt(4);
    data.writeFloat(value.floatValue());
  }

  private static void doubleWriter(DataOutputStream data, Double value) throws IOException {
    data.writeInt(8);
    data.writeDouble(value.doubleValue());
  }

  private static void longWriter(DataOutputStream data, Long value) throws IOException {
    data.writeInt(8);
    data.writeLong(value.longValue());
  }

  private static void stringWriter(DataOutputStream data, String value) throws IOException {
    byte[] bytes = value.getBytes(UTF8);
    data.writeInt(bytes.length);
    data.write(bytes);
  }

  private static void localDateWriter(DataOutputStream data, LocalDate value) throws IOException {
    data.writeInt(4);
    data.writeInt(TimestampUtils.toPgDays(value));
  }

  private static void localDateTimeWriter(DataOutputStream data, LocalDateTime value) throws IOException {
    data.writeInt(8);
    data.writeLong(TimestampUtils.toPgSecs(value));
  }

  private static void inet4AdressWriter(DataOutputStream data, Inet4Address value) throws IOException {
    data.writeInt(8);
    data.writeByte(IPV4);
    data.writeByte(IPV4_MASK);
    data.writeByte(IPV4_IS_CIDR);
    byte[] inet4AddressBytes = value.getAddress();
    data.writeByte(inet4AddressBytes.length);
    data.write(inet4AddressBytes);
  }

  private static void inet6AdressWriter(DataOutputStream data, Inet6Address value) throws IOException {
    data.writeInt(20);
    data.writeByte(IPV6);
    data.writeByte(IPV6_MASK);
    data.writeByte(IPV6_IS_CIDR);
    byte[] inet6AddressBytes = value.getAddress();
    data.writeByte(inet6AddressBytes.length);
    data.write(inet6AddressBytes);
  }

  private <T> ValueWriter<List<T>> collectionWriter(int oid, ValueWriter<T> writer) {
    return (data, values) -> {
      // Write into a temporary byte array
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

      // Use 1 for one-dimensional arrays
      dataOutputStream.writeInt(1);

      // The collection can contain null values
      dataOutputStream.writeInt(1);

      // Write the values using the OID
      dataOutputStream.writeInt(oid);

      // Write the number of elements
      dataOutputStream.writeInt(values.size());

      // Ignore Lower Bound. Use PG Default for now
      dataOutputStream.writeInt(1);

      // Iterate over the collection and write each values
      for (T value : values) {
        writer.write(dataOutputStream, value);
      }

      // Write the entire array to the COPY data:
      data.writeInt(byteArrayOutputStream.size());
      data.write(byteArrayOutputStream.toByteArray());
    };
  }

  private static void hstoreWriter(DataOutputStream data, Map<String, String> value) throws IOException {
    // Write into a temporary byte array
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

    // Write the number of values to write
    dataOutputStream.writeInt(value.size());

    // Iterate over the map and write each key value pairs
    for (Map.Entry<String, String> entry : value.entrySet()) {
      stringWriter(dataOutputStream, entry.getKey());
      stringWriter(dataOutputStream, entry.getValue());
    }

    // Write the entire array to the COPY data
    data.writeInt(byteArrayOutputStream.size());
    data.write(byteArrayOutputStream.toByteArray());
  }

  @FunctionalInterface
  private interface ValueWriter<T> {

    void write(DataOutputStream data, T value) throws IOException;

  }

}
