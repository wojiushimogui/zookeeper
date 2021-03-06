/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.common.Time;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataNode;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileHeader;
import org.apache.zookeeper.server.persistence.FileTxnLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.server.persistence.FileTxnLog.FileTxnIterator;
import org.apache.zookeeper.server.persistence.TxnLog.TxnIterator;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadFromLogTest extends ZKTestCase {
    private static final String HOST = "127.0.0.1:";
    private static final int CONNECTION_TIMEOUT = 3000;
    private static final int NUM_MESSAGES = 300;
    protected static final Logger LOG = LoggerFactory.getLogger(LoadFromLogTest.class);

    // setting up the quorum has a transaction overhead for creating and closing the session
    private static final int TRANSACTION_OVERHEAD = 2;
    private static final int TOTAL_TRANSACTIONS = NUM_MESSAGES + TRANSACTION_OVERHEAD;

    /**
     * test that all transactions from the Log are loaded, and only once
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testLoad() throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(100);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(hostPort,CONNECTION_TIMEOUT));
        ZooKeeper zk = ClientBase.createZKClient(hostPort);

        // generate some transactions that will get logged
        try {
            for (int i = 0; i< NUM_MESSAGES; i++) {
                zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(hostPort, CONNECTION_TIMEOUT));

        // now verify that the FileTxnLog reads every transaction only once
        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        FileTxnLog txnLog = new FileTxnLog(logDir);

        TxnIterator itr = txnLog.read(0);
        
        // Check that storage space return some value
        FileTxnIterator fileItr = (FileTxnIterator) itr;
        long storageSize = fileItr.getStorageSize();
        LOG.info("Txnlog size: " + storageSize + " bytes");
        Assert.assertTrue("Storage size is greater than zero ",
                (storageSize > 0));
        
        long expectedZxid = 0;
        long lastZxid = 0;
        TxnHeader hdr;
        do {
            hdr = itr.getHeader();
            expectedZxid++;
            Assert.assertTrue("not the same transaction. lastZxid=" + lastZxid + ", zxid=" + hdr.getZxid(), lastZxid != hdr.getZxid());
            Assert.assertTrue("excepting next transaction. expected=" + expectedZxid + ", retreived=" + hdr.getZxid(), (hdr.getZxid() == expectedZxid));
            lastZxid = hdr.getZxid();
        }while(itr.next());

        Assert.assertTrue("processed all transactions. " + expectedZxid + " == " + TOTAL_TRANSACTIONS, (expectedZxid == TOTAL_TRANSACTIONS));
        zks.shutdown();
    }

    /**
     * test that we fail to load txnlog of a request zxid that is older
     * than what exist on disk
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testLoadFailure() throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        // So we have at least 4 logs
        SyncRequestProcessor.setSnapCount(50);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(hostPort,CONNECTION_TIMEOUT));
        ZooKeeper zk = ClientBase.createZKClient(hostPort);

        // generate some transactions that will get logged
        try {
            for (int i = 0; i< NUM_MESSAGES; i++) {
                zk.create("/data-", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(hostPort, CONNECTION_TIMEOUT));

        File logDir = new File(tmpDir, FileTxnSnapLog.version + FileTxnSnapLog.VERSION);
        File[] logFiles = FileTxnLog.getLogFiles(logDir.listFiles(), 0);
        // Verify that we have at least 4 txnlog
        Assert.assertTrue(logFiles.length > 4);
        // Delete the first log file, so we will fail to read it back from disk
        Assert.assertTrue("delete the first log file", logFiles[0].delete());

        // Find zxid for the second log
        long secondStartZxid = Util.getZxidFromName(logFiles[1].getName(), "log");

        FileTxnLog txnLog = new FileTxnLog(logDir);
        TxnIterator itr = txnLog.read(1, false);

        // Oldest log is already remove, so this should point to the start of
        // of zxid on the second log
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());

        itr = txnLog.read(secondStartZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());
        Assert.assertTrue(itr.next());

        // Trying to get a second txn on second txnlog give us the
        // the start of second log, since the first one is removed
        long nextZxid = itr.getHeader().getZxid();

        itr = txnLog.read(nextZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());

        // Trying to get a first txn on the third give us the
        // the start of second log, since the first one is removed
        long thirdStartZxid = Util.getZxidFromName(logFiles[2].getName(), "log");
        itr = txnLog.read(thirdStartZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());
        Assert.assertTrue(itr.next());

        nextZxid = itr.getHeader().getZxid();
        itr = txnLog.read(nextZxid, false);
        Assert.assertEquals(secondStartZxid, itr.getHeader().getZxid());

    }

    /**
     * For ZOOKEEPER-1046. Verify if cversion and pzxid if incremented
     * after create/delete failure during restore.
     */
    @Test
    public void testTxnFailure() throws Exception {
        long count = 1;
        File tmpDir = ClientBase.createTmpDir();
        FileTxnSnapLog logFile = new FileTxnSnapLog(tmpDir, tmpDir);
        DataTree dt = new DataTree();
        dt.createNode("/test", new byte[0], null, 0, -1, 1, 1);
        for (count = 1; count <= 3; count++) {
            dt.createNode("/test/" + count, new byte[0], null, 0, -1, count,
                    Time.currentElapsedTime());
        }
        DataNode zk = dt.getNode("/test");

        // Make create to fail, then verify cversion.
        LOG.info("Attempting to create " + "/test/" + (count - 1));
        doOp(logFile, OpCode.create, "/test/" + (count - 1), dt, zk, -1);

        LOG.info("Attempting to create " + "/test/" + (count - 1));
        doOp(logFile, OpCode.create, "/test/" + (count - 1), dt, zk,
                zk.stat.getCversion() + 1);

        LOG.info("Attempting to create " + "/test/" + (count - 1));
        doOp(logFile, OpCode.multi, "/test/" + (count - 1), dt, zk,
                zk.stat.getCversion() + 1);

        LOG.info("Attempting to create " + "/test/" + (count - 1));
        doOp(logFile, OpCode.multi, "/test/" + (count - 1), dt, zk,
                -1);

        // Make delete fo fail, then verify cversion.
        // this doesn't happen anymore, we only set the cversion on create
        // LOG.info("Attempting to delete " + "/test/" + (count + 1));
        // doOp(logFile, OpCode.delete, "/test/" + (count + 1), dt, zk);
    }
    /*
     * Does create/delete depending on the type and verifies
     * if cversion before the operation is 1 less than cversion afer.
     */
    private void doOp(FileTxnSnapLog logFile, int type, String path,
            DataTree dt, DataNode parent, int cversion) throws Exception {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);

        int prevCversion = parent.stat.getCversion();
        long prevPzxid = parent.stat.getPzxid();
        List<String> child = dt.getChildren(parentName, null, null);
        StringBuilder childStr = new StringBuilder();
        for (String s : child) {
            childStr.append(s).append(" ");
        }
        LOG.info("Children: " + childStr + " for " + parentName);
        LOG.info("(cverions, pzxid): " + prevCversion + ", " + prevPzxid);

        Record txn = null;
        TxnHeader txnHeader = null;
        if (type == OpCode.delete) {
            txn = new DeleteTxn(path);
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                Time.currentElapsedTime(), OpCode.delete);
        } else if (type == OpCode.create) {
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                    Time.currentElapsedTime(), OpCode.create);
            txn = new CreateTxn(path, new byte[0], null, false, cversion);
        }
        else if (type == OpCode.multi) {
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                    Time.currentElapsedTime(), OpCode.create);
            txn = new CreateTxn(path, new byte[0], null, false, cversion);
            List<Txn> txnList = new ArrayList<Txn>();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            txn.serialize(boa, "request") ;
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            Txn txact = new Txn(OpCode.create,  bb.array());
            txnList.add(txact);
            txn = new MultiTxn(txnList);
            txnHeader = new TxnHeader(0xabcd, 0x123, prevPzxid + 1,
                    Time.currentElapsedTime(), OpCode.multi);
        }
        logFile.processTransaction(txnHeader, dt, null, txn);

        int newCversion = parent.stat.getCversion();
        long newPzxid = parent.stat.getPzxid();
        child = dt.getChildren(parentName, null, null);
        childStr = new StringBuilder();
        for (String s : child) {
            childStr.append(s).append(" ");
        }
        LOG.info("Children: " + childStr + " for " + parentName);
        LOG.info("(cverions, pzxid): " +newCversion + ", " + newPzxid);
        Assert.assertTrue(type + " <cversion, pzxid> verification failed. Expected: <" +
                (prevCversion + 1) + ", " + (prevPzxid + 1) + ">, found: <" +
                newCversion + ", " + newPzxid + ">",
                (newCversion == prevCversion + 1 && newPzxid == prevPzxid + 1));
    }
    /**
     * Simulates ZOOKEEPER-1069 and verifies that flush() before padLogFile
     * fixes it.
     */
    @Test
    public void testPad() throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        FileTxnLog txnLog = new FileTxnLog(tmpDir);
        TxnHeader txnHeader = new TxnHeader(0xabcd, 0x123, 0x123,
              Time.currentElapsedTime(), OpCode.create);
        Record txn = new CreateTxn("/Test", new byte[0], null, false, 1);
        txnLog.append(txnHeader, txn);
        FileInputStream in = new FileInputStream(tmpDir.getPath() + "/log." +
              Long.toHexString(txnHeader.getZxid()));
        BinaryInputArchive ia  = BinaryInputArchive.getArchive(in);
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        LOG.info("Received magic : " + header.getMagic() +
              " Expected : " + FileTxnLog.TXNLOG_MAGIC);
        Assert.assertTrue("Missing magic number ",
              header.getMagic() == FileTxnLog.TXNLOG_MAGIC);
    }

    /**
     * Test we can restore the snapshot that has data ahead of the zxid
     * of the snapshot file.
     */
    @Test
    public void testRestore() throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        ZooKeeper zk = getConnectedZkClient(hostPort);

        // generate some transactions
        String lastPath = null;
        try {
            zk.create("/invalidsnap", new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            for (int i = 0; i < NUM_MESSAGES; i++) {
                lastPath = zk.create("/invalidsnap/test-", new byte[0],
                        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
            }
        } finally {
            zk.close();
        }
        String[] tokens = lastPath.split("-");
        String expectedPath = "/invalidsnap/test-"
                + String.format("%010d",
                (new Integer(tokens[1])).intValue() + 1);
        long eZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
        // force the zxid to be behind the content
        zks.getZKDatabase().setlastProcessedZxid(
                zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to "
                + zks.getZKDatabase().getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        f.shutdown();

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        long fZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();

        // Verify lastProcessedZxid is set correctly
        Assert.assertTrue("Restore failed expected zxid=" + eZxid + " found="
                + fZxid, fZxid == eZxid);
        zk = getConnectedZkClient(hostPort);

        // Verify correctness of data and whether sequential znode creation
        // proceeds correctly after this point
        String[] children;
        String path;
        try {
            children = zk.getChildren("/invalidsnap", false).toArray(
                    new String[0]);
            path = zk.create("/invalidsnap/test-", new byte[0],
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        } finally {
            zk.close();
        }
        LOG.info("Expected " + expectedPath + " found " + path);
        Assert.assertTrue("Error in sequential znode creation expected "
                + expectedPath + " found " + path, path.equals(expectedPath));
        Assert.assertTrue("Unexpected number of children " + children.length
                        + " expected " + NUM_MESSAGES,
                (children.length == NUM_MESSAGES));
        f.shutdown();
        zks.shutdown();
    }

    /**
     * Test we can restore a snapshot that has errors and data ahead of the zxid
     * of the snapshot file.
     */
    @Test
    public void testRestoreWithTransactionErrors() throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        ZooKeeper zk = getConnectedZkClient(hostPort);

        // generate some transactions
        try {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                try {
                    zk.create("/invaliddir/test-", new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
                } catch(NoNodeException e) {
                    //Expected
                }
            }
        } finally {
            zk.close();
        }

        // force the zxid to be behind the content
        zks.getZKDatabase().setlastProcessedZxid(
                zks.getZKDatabase().getDataTreeLastProcessedZxid() - 10);
        LOG.info("Set lastProcessedZxid to "
                + zks.getZKDatabase().getDataTreeLastProcessedZxid());

        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        f.shutdown();

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(hostPort, CONNECTION_TIMEOUT));

        f.shutdown();
        zks.shutdown();
    }

    /**
     * Verify snap/log dir create with/without autocreate enabled.
     */
    @Test
    public void testDatadirAutocreate() throws Exception {
        ClientBase.setupTestEnv();
        final String hostPort = HOST + PortAssignment.unique();
        // first verify the default (autocreate on) works
        File tmpDir = ClientBase.createTmpDir();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ", ClientBase
                .waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        zks.shutdown();
        f.shutdown();
        Assert.assertTrue("waiting for server being down ", ClientBase
                .waitForServerDown(hostPort, CONNECTION_TIMEOUT));

        try {
            // now verify autocreate off works
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE, "false");

            tmpDir = ClientBase.createTmpDir();
            zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
            f = ServerCnxnFactory.createFactory(PORT, -1);
            f.startup(zks);
            Assert.assertTrue("waiting for server being up ", ClientBase
                    .waitForServerUp(hostPort, CONNECTION_TIMEOUT));

            Assert.fail("Server should not have started without datadir");
        } catch (IOException e) {
            LOG.info("Server failed to start - correct behavior " + e);
        } finally {
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE,
                FileTxnSnapLog.ZOOKEEPER_DATADIR_AUTOCREATE_DEFAULT);
        }
    }

    /**
     * ZOOKEEPER-1573: test restoring a snapshot with deleted txns ahead of the
     * snapshot file's zxid.
     */
    @Test
    public void testReloadSnapshotWithMissingParent() throws Exception {
        final String hostPort = HOST + PortAssignment.unique();
        // setup a single server cluster
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        final int PORT = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        ZooKeeper zk = getConnectedZkClient(hostPort);

        // create transactions to create the snapshot with create/delete pattern
        zk.create("/a", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Stat stat = zk.exists("/a", false);
        long createZxId = stat.getMzxid();
        zk.create("/a/b", "".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        zk.delete("/a/b", -1);
        zk.delete("/a", -1);
        // force the zxid to be behind the content
        zks.getZKDatabase().setlastProcessedZxid(createZxId);
        LOG.info("Set lastProcessedZxid to {}", zks.getZKDatabase()
                .getDataTreeLastProcessedZxid());
        // Force snapshot and restore
        zks.takeSnapshot();
        zks.shutdown();
        f.shutdown();

        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        SyncRequestProcessor.setSnapCount(10000);
        f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(hostPort, CONNECTION_TIMEOUT));
        f.shutdown();
    }

    private ZooKeeper getConnectedZkClient(String host) throws Exception {
        ZooKeeper zk = ClientBase.createZKClient(host);
        return zk;
    }
}
