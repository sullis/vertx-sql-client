/*
 * Copyright (c) 2011-2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.oracleclient.test;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.oracleclient.OracleClient;
import io.vertx.oracleclient.OraclePool;
import io.vertx.oracleclient.OraclePrepareOptions;
import io.vertx.oracleclient.test.junit.OracleRule;
import io.vertx.sqlclient.*;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(VertxUnitRunner.class)
public abstract class OracleGeneratedKeysTestBase extends OracleTestBase {

  private static final String DROP = "DROP TABLE EntityWithIdentity";
  private static final String CREATE = "CREATE TABLE EntityWithIdentity\n" +
    "(\n" +
    "    id             NUMBER(19, 0) GENERATED AS IDENTITY,\n" +
    "    name           VARCHAR2(255 CHAR),\n" +
    "    position       NUMBER(10, 0),\n" +
    "    string         VARCHAR2(255)            DEFAULT 'default'                              NOT NULL,\n" +
    "    localDate      DATE                     DEFAULT date '2019-11-04'                      NOT NULL,\n" +
    "    localDateTime  TIMESTAMP                DEFAULT timestamp '2018-11-04 00:00:00'        NOT NULL,\n" +
    "    inte           NUMBER(10)               DEFAULT 42                                     NOT NULL,\n" +
    "    longe          NUMBER(19)               DEFAULT 84                                     NOT NULL,\n" +
    "    floate         BINARY_FLOAT             DEFAULT '42.42'                                NOT NULL,\n" +
    "    doublee        BINARY_DOUBLE            DEFAULT '84.84'                                NOT NULL,\n" +
    "    bigDecimal     NUMBER(3, 1)             DEFAULT '4.2'                                  NOT NULL,\n" +
    "    offsetDateTime TIMESTAMP WITH TIME ZONE DEFAULT timestamp '2019-11-04 00:00:00 +01:02' NOT NULL,\n" +
    "    PRIMARY KEY (id)\n" +
    ")";
  private static final String INSERT = "INSERT INTO EntityWithIdentity (name, position) VALUES (?, ?)";

  private static final List<Tuple> GENERATED_COLUMNS;

  static {
    GENERATED_COLUMNS = List.of(
      Tuple.of("ID", 1),
      Tuple.of("STRING", 4, "default"),
      Tuple.of("LOCALDATE", 5, LocalDateTime.of(2019, 11, 4, 0, 0)),
      Tuple.of("LOCALDATETIME", 6, LocalDateTime.of(2018, 11, 4, 0, 0)),
      Tuple.of("INTE", 7, new BigDecimal("42")),
      Tuple.of("LONGE", 8, new BigDecimal("84")),
      Tuple.of("FLOATE", 9, 42.42F),
      Tuple.of("DOUBLEE", 10, 84.84D),
      Tuple.of("BIGDECIMAL", 11, new BigDecimal("4.2")),
      Tuple.of("OFFSETDATETIME", 12, OffsetDateTime.of(LocalDateTime.of(2019, 11, 4, 0, 0), ZoneOffset.ofHoursMinutes(1, 2)))
    );
  }

  @ClassRule
  public static OracleRule oracle = OracleRule.SHARED_INSTANCE;

  protected OraclePool pool;

  @Before
  public void setUp(TestContext ctx) throws Exception {
    pool = OraclePool.pool(vertx, oracle.options(), new PoolOptions());
    pool
      .withConnection(conn -> {
      return conn
        .query(DROP)
        .execute()
        .otherwiseEmpty()
        .compose(v -> conn.query(CREATE).execute());
    })
      .onComplete(ctx.asyncAssertSuccess());
  }

  @Test
  public void shouldRetrieveRowId(TestContext ctx) {
    doTest(ctx, () -> {
      return new OraclePrepareOptions().setAutoGeneratedKeys(true);
    }, generated -> {
      assertNotNull(generated);
      assertEquals(1, generated.size());
      verifyGeneratedId(generated, "ROWID", byte[].class);
    });
  }

  @Test
  public void shouldRetrieveGeneratedKeyByName(TestContext ctx) {
    doTest(ctx, () -> {
      JsonArray indexes = GENERATED_COLUMNS.stream().map(tuple -> tuple.getString(0)).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
      return new OraclePrepareOptions().setAutoGeneratedKeysIndexes(indexes);
    }, generated -> {
      assertNotNull(generated);
      assertEquals(10, generated.size());
      verifyGeneratedId(generated, "ID", Number.class);
      verifyGeneratedColumns(generated);
    });
  }

  @Test
  public void shouldRetrieveGeneratedKeyByIndex(TestContext ctx) {
    doTest(ctx, () -> {
      JsonArray indexes = GENERATED_COLUMNS.stream().map(tuple -> tuple.getInteger(1)).collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
      return new OraclePrepareOptions().setAutoGeneratedKeysIndexes(indexes);
    }, generated -> {
      assertNotNull(generated);
      assertEquals(10, generated.size());
      verifyGeneratedId(generated, "ID", Number.class);
      verifyGeneratedColumns(generated);
    });
  }

  private void doTest(TestContext ctx, Supplier<OraclePrepareOptions> supplier, Consumer<Row> checks) {
    OraclePrepareOptions options = supplier.get();
    withSqlClient(client -> {
      return client.preparedQuery(INSERT, options).execute(Tuple.of("bar", 4));
    }, ctx.asyncAssertSuccess(rows -> ctx.verify(v -> {
      checks.accept(rows.property(OracleClient.GENERATED_KEYS));
    })));
    if (options != null) {
      withSqlClient(client -> {
        return client.preparedQuery(INSERT, new PrepareOptions(options.toJson())).execute(Tuple.of("foo", 3));
      }, ctx.asyncAssertSuccess(rows -> ctx.verify(v -> {
        checks.accept(rows.property(OracleClient.GENERATED_KEYS));
      })));
    }
  }

  protected abstract <T> void withSqlClient(Function<SqlClient, Future<T>> function, Handler<AsyncResult<T>> handler);

  private void verifyGeneratedId(Row generated, String expectedColumnName, Class<?> expectedClass) {
    assertEquals(expectedColumnName, generated.getColumnName(0));
    assertThat(generated.getValue(expectedColumnName), is(instanceOf(expectedClass)));
  }

  private void verifyGeneratedColumns(Row generated) {
    for (int i = 1; i < GENERATED_COLUMNS.size(); i++) {
      Tuple tuple = GENERATED_COLUMNS.get(i);
      assertEquals(tuple.getString(0), generated.getColumnName(i));
      assertEquals(tuple.getValue(2), generated.getValue(i));
    }
  }

  @After
  public void tearDown(TestContext ctx) throws Exception {
    pool.close().onComplete(ctx.asyncAssertSuccess());
  }
}
