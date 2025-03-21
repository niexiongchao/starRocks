// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/catalog/AdminStmtTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.common.collect.Lists;
import com.starrocks.analysis.AdminSetReplicaStatusStmt;
import com.starrocks.analysis.CreateDbStmt;
import com.starrocks.analysis.CreateTableStmt;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.Replica.ReplicaStatus;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Pair;
import com.starrocks.persist.SetReplicaStatusOperationLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class AdminStmtTest {

    // use a unique dir so that it won't be conflict with other unit test which
    // may also start a Mocked Frontend
    private static String runningDir = "fe/mocked/AdminStmtTest/" + UUID.randomUUID().toString() + "/";

    private static ConnectContext connectContext;

    @BeforeClass
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster(runningDir);

        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        // create database
        String createDbStmtStr = "create database test;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseAndAnalyzeStmt(createDbStmtStr, connectContext);
        Catalog.getCurrentCatalog().createDb(createDbStmt);

        String sql = "CREATE TABLE test.tbl1 (\n" +
                "  `id` int(11) NULL COMMENT \"\",\n" +
                "  `id2` bitmap bitmap_union NULL\n" +
                ") ENGINE=OLAP\n" +
                "AGGREGATE KEY(`id`)\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                " \"replication_num\" = \"1\"\n" +
                ");";
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseAndAnalyzeStmt(sql, connectContext);
        Catalog.getCurrentCatalog().createTable(createTableStmt);
    }

    @AfterClass
    public static void tearDown() {
        File file = new File(runningDir);
        file.delete();
    }

    @Test
    public void testAdminSetReplicaStatus() throws Exception {
        Database db = Catalog.getCurrentCatalog().getDb("default_cluster:test");
        Assert.assertNotNull(db);
        OlapTable tbl = (OlapTable) db.getTable("tbl1");
        Assert.assertNotNull(tbl);
        // tablet id, backend id
        List<Pair<Long, Long>> tabletToBackendList = Lists.newArrayList();
        for (Partition partition : tbl.getPartitions()) {
            for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.VISIBLE)) {
                for (Tablet tablet : index.getTablets()) {
                    for (Replica replica : ((LocalTablet) tablet).getReplicas()) {
                        tabletToBackendList.add(Pair.create(tablet.getId(), replica.getBackendId()));
                    }
                }
            }
        }
        Assert.assertEquals(3, tabletToBackendList.size());
        long tabletId = tabletToBackendList.get(0).first;
        long backendId = tabletToBackendList.get(0).second;
        Replica replica = Catalog.getCurrentInvertedIndex().getReplica(tabletId, backendId);
        Assert.assertFalse(replica.isBad());

        // set replica to bad
        String adminStmt = "admin set replica status properties ('tablet_id' = '" + tabletId + "', 'backend_id' = '"
                + backendId + "', 'status' = 'bad');";
        AdminSetReplicaStatusStmt stmt =
                (AdminSetReplicaStatusStmt) UtFrameUtils.parseAndAnalyzeStmt(adminStmt, connectContext);
        Catalog.getCurrentCatalog().setReplicaStatus(stmt);
        replica = Catalog.getCurrentInvertedIndex().getReplica(tabletId, backendId);
        Assert.assertTrue(replica.isBad());

        // set replica to ok
        adminStmt = "admin set replica status properties ('tablet_id' = '" + tabletId + "', 'backend_id' = '"
                + backendId + "', 'status' = 'ok');";
        stmt = (AdminSetReplicaStatusStmt) UtFrameUtils.parseAndAnalyzeStmt(adminStmt, connectContext);
        Catalog.getCurrentCatalog().setReplicaStatus(stmt);
        replica = Catalog.getCurrentInvertedIndex().getReplica(tabletId, backendId);
        Assert.assertFalse(replica.isBad());
    }

    @Test
    public void testSetReplicaStatusOperationLog() throws IOException, AnalysisException {
        String fileName = "./SetReplicaStatusOperationLog";
        try {
            // 1. Write objects to file
            File file = new File(fileName);
            file.createNewFile();
            DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

            SetReplicaStatusOperationLog log = new SetReplicaStatusOperationLog(10000, 100001, ReplicaStatus.BAD);
            log.write(out);
            out.flush();
            out.close();

            // 2. Read objects from file
            DataInputStream in = new DataInputStream(new FileInputStream(file));

            SetReplicaStatusOperationLog readLog = SetReplicaStatusOperationLog.read(in);
            Assert.assertEquals(log.getBackendId(), readLog.getBackendId());
            Assert.assertEquals(log.getTabletId(), readLog.getTabletId());
            Assert.assertEquals(log.getReplicaStatus(), readLog.getReplicaStatus());

            in.close();
        } finally {
            File file = new File(fileName);
            file.delete();
        }
    }

}
