/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.line.tcp;

import org.junit.Assert;
import org.junit.Test;

import io.questdb.cairo.AbstractCairoTest;
import io.questdb.cairo.CairoTestUtils;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.PartitionBy;
import io.questdb.cairo.ReadOnlyMemory;
import io.questdb.cairo.TableModel;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;

public class SymbolCacheTest extends AbstractCairoTest {
    @Test
    public void test() throws Exception {
        String tableName = "tb1";
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = new Path();
                    TableModel model = new TableModel(configuration, tableName, PartitionBy.DAY)
                            .col("symCol1", ColumnType.SYMBOL)
                            .col("symCol2", ColumnType.SYMBOL);
                    SymbolCache cache = new SymbolCache()) {
                CairoTestUtils.create(model);
                try (TableWriter writer = new TableWriter(configuration, tableName);
                        ReadOnlyMemory txMem = new ReadOnlyMemory()) {

                    int symColIndex1 = writer.getColumnIndex("symCol1");
                    int symColIndex2 = writer.getColumnIndex("symCol2");
                    long symCountOffset = TableUtils.getSymbolWriterIndexOffset(symColIndex2);
                    long transientSymCountOffset = TableUtils.getSymbolWriterTransientIndexOffset(symColIndex2);
                    path.of(configuration.getRoot()).concat(tableName);
                    txMem.of(configuration.getFilesFacade(), path.concat(TableUtils.TXN_FILE_NAME).$(), configuration.getFilesFacade().getPageSize(),
                            transientSymCountOffset + Integer.BYTES);
                    cache.of(configuration, path.of(configuration.getRoot()).concat(tableName), "symCol2", symColIndex2);

                    TableWriter.Row r = writer.newRow();
                    r.putSym(symColIndex1, "sym11");
                    r.putSym(symColIndex2, "sym21");
                    r.append();
                    writer.commit();
                    Assert.assertEquals(1, txMem.getInt(symCountOffset));
                    Assert.assertEquals(1, txMem.getInt(transientSymCountOffset));
                    int rc = cache.getSymIndex("missing");
                    Assert.assertEquals(SymbolTable.VALUE_NOT_FOUND, rc);
                    Assert.assertEquals(0, cache.getNCached());
                    rc = cache.getSymIndex("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getNCached());

                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym21");
                    r.append();
                    writer.commit();
                    Assert.assertEquals(1, txMem.getInt(symCountOffset));
                    Assert.assertEquals(1, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("missing");
                    Assert.assertEquals(SymbolTable.VALUE_NOT_FOUND, rc);
                    Assert.assertEquals(1, cache.getNCached());
                    rc = cache.getSymIndex("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getNCached());

                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym22");
                    r.append();
                    Assert.assertEquals(1, txMem.getInt(symCountOffset));
                    Assert.assertEquals(2, txMem.getInt(transientSymCountOffset));
                    writer.commit();
                    Assert.assertEquals(2, txMem.getInt(symCountOffset));
                    Assert.assertEquals(2, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("sym21");
                    Assert.assertEquals(0, rc);
                    Assert.assertEquals(1, cache.getNCached());
                    rc = cache.getSymIndex("sym22");
                    Assert.assertEquals(1, rc);
                    Assert.assertEquals(2, cache.getNCached());

                    // Test cached uncommitted symbols
                    r = writer.newRow();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym23");
                    r.append();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym24");
                    r.append();
                    r.putSym(symColIndex1, "sym12");
                    r.putSym(symColIndex2, "sym25");
                    r.append();
                    Assert.assertEquals(2, txMem.getInt(symCountOffset));
                    Assert.assertEquals(5, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("sym22");
                    Assert.assertEquals(1, rc);
                    Assert.assertEquals(2, cache.getNCached());
                    rc = cache.getSymIndex("sym24");
                    Assert.assertEquals(3, rc);
                    Assert.assertEquals(3, cache.getNCached());
                    writer.commit();
                    Assert.assertEquals(5, txMem.getInt(symCountOffset));
                    Assert.assertEquals(5, txMem.getInt(transientSymCountOffset));

                    // Test deleting a symbol column
                    writer.removeColumn("symCol1");
                    cache.close();
                    txMem.close();
                    symColIndex1 = -1;
                    symColIndex2 = writer.getColumnIndex("symCol2");
                    symCountOffset = TableUtils.getSymbolWriterIndexOffset(symColIndex2);
                    transientSymCountOffset = TableUtils.getSymbolWriterTransientIndexOffset(symColIndex2);
                    path.of(configuration.getRoot()).concat(tableName);
                    txMem.of(configuration.getFilesFacade(), path.concat(TableUtils.TXN_FILE_NAME).$(), configuration.getFilesFacade().getPageSize(),
                            transientSymCountOffset + Integer.BYTES);
                    cache.of(configuration, path.of(configuration.getRoot()).concat(tableName), "symCol2", symColIndex2);

                    Assert.assertEquals(5, txMem.getInt(symCountOffset));
                    Assert.assertEquals(5, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("sym24");
                    Assert.assertEquals(3, rc);
                    Assert.assertEquals(1, cache.getNCached());

                    r = writer.newRow();
                    r.putSym(symColIndex2, "sym26");
                    r.append();
                    Assert.assertEquals(5, txMem.getInt(symCountOffset));
                    Assert.assertEquals(6, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("sym26");
                    Assert.assertEquals(5, rc);
                    Assert.assertEquals(2, cache.getNCached());
                    writer.commit();
                    Assert.assertEquals(6, txMem.getInt(symCountOffset));
                    Assert.assertEquals(6, txMem.getInt(transientSymCountOffset));
                    rc = cache.getSymIndex("sym26");
                    Assert.assertEquals(5, rc);
                    Assert.assertEquals(2, cache.getNCached());
                }
            }
        });
    }
}
