// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/mysql/MysqlServerTest.java

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

package com.starrocks.mysql;

import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ConnectScheduler;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class MysqlServerTest {
    private static final Logger LOG = LoggerFactory.getLogger(MysqlServerTest.class);

    private final AtomicInteger submitNum = new AtomicInteger(0);
    private final AtomicInteger submitFailNum = new AtomicInteger(0);
    @Mocked
    private ConnectScheduler scheduler;
    @Mocked
    private ConnectScheduler badScheduler;

    @Before
    public void setUp() {
        submitNum.set(0);
        submitFailNum.set(0);
        new Expectations() {
            {
                scheduler.submit((ConnectContext) any);
                minTimes = 0;
                result = new Delegate() {
                    public Boolean answer() throws Throwable {
                        LOG.info("answer.");
                        submitNum.getAndIncrement();
                        return Boolean.TRUE;
                    }
                };

                badScheduler.submit((ConnectContext) any);
                minTimes = 0;
                result = new Delegate() {
                    public Boolean answer() throws Throwable {
                        LOG.info("answer.");
                        submitFailNum.getAndIncrement();
                        return Boolean.FALSE;
                    }
                };
            }
        };
    }

    @Test
    public void testInvalidParam() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        socket.close();
        MysqlServer server = new MysqlServer(port, null);
        Assert.assertFalse(server.start());
    }

    @Test
    public void testBindFail() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        socket.close();
        MysqlServer server = new MysqlServer(port, scheduler);
        Assert.assertTrue(server.start());
        MysqlServer server1 = new MysqlServer(port, scheduler);
        Assert.assertFalse(server1.start());

        server.stop();
        server.join();
    }

    @Test
    public void testSubFail() throws IOException, InterruptedException {
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        socket.close();
        MysqlServer server = new MysqlServer(port, badScheduler);
        Assert.assertTrue(server.start());

        // submit
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("127.0.0.1", port));
        // sleep to wait mock process
        Thread.sleep(1000);
        channel.close();

        // submit twice
        channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("127.0.0.1", port));
        // sleep to wait mock process
        Thread.sleep(1000);
        channel.close();

        // stop and join
        server.stop();
        server.join();

        Assert.assertEquals(2, submitFailNum.get());
    }

}