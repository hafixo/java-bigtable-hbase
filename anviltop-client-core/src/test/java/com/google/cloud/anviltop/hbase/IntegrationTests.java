package com.google.cloud.anviltop.hbase;

import com.google.api.client.util.Strings;
import com.google.common.base.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    TestAppend.class,
    TestAutoFlush.class,
    TestBasicOps.class,
    TestBatch.class,
    TestCheckAndMutate.class,
    TestCreateTable.class,
    TestDisableTable.class,
    TestDelete.class,
    TestDurability.class,
    TestFilters.class,
    TestGet.class,
    TestGetTable.class,
    TestScan.class,
    TestIncrement.class,
    TestListTables.class,
    TestPut.class,
    TestTimestamp.class,
    TestImport.class
})
public class IntegrationTests {

  public static final TableName TABLE_NAME;
  public static final int MAX_VERSIONS = 6;
  public static final byte[] COLUMN_FAMILY = Bytes.toBytes("test_family");
  public static final Configuration BASE_CONFIGURATION = HBaseConfiguration.create();

  // testingUtility, connection, and configuration are provided via the connectionResource later
  protected static HBaseTestingUtility testingUtility;
  protected static HConnection connection;
  protected static Configuration configuration;

  static {
    TABLE_NAME = newTestTableName();
    addExtraResources(BASE_CONFIGURATION);
  }

  public static TableName newTestTableName() {
    return TableName.valueOf("test_table-" + UUID.randomUUID().toString());
  }

  protected static void addExtraResources(Configuration configuration) {
    String extraResources = System.getProperty("anviltop.test.extra.resources");
    if (extraResources != null) {
      InputStream resourceStream =
          AbstractTest.class.getClassLoader().getResourceAsStream(extraResources);
      if (resourceStream != null) {
        configuration.addResource(resourceStream);
      }
    }
    String projectId = configuration.get(AnviltopOptionsFactory.PROJECT_ID_KEY);
    if (projectId != null) {
      SecureRandom random = new SecureRandom();
      configuration.set(AnviltopOptionsFactory.PROJECT_ID_KEY, projectId + "-" + random.nextInt());
    }
  }

  protected static boolean useMiniCluster() {
    return Strings.isNullOrEmpty(
        BASE_CONFIGURATION.get(HConnection.HBASE_CLIENT_CONNECTION_IMPL, ""));
  }

  public static MiniDFSCluster getMiniCluster() {
    return testingUtility.getDFSCluster();
  }

  private static Admin getAdmin() throws IOException {
    HConnection connection = HConnectionManager.createConnection(configuration);
    return connection.getAdmin();
  }

  public static Configuration getConfiguration() {
    // If the class rule hasn't run, we probably haven't created our table yet either.
    Preconditions.checkState(configuration != null,
        "Integration test class rule not run, configuration not initialized.");
    return configuration;
  }

  @ClassRule
  public static Timeout timeoutRule = new Timeout((int) TimeUnit.MINUTES.toMillis(5));

  public static void createTable(TableName tableName) throws IOException {
    Admin admin = getAdmin();
    HColumnDescriptor hcd = new HColumnDescriptor(COLUMN_FAMILY).setMaxVersions(MAX_VERSIONS);
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(hcd);
    admin.createTable(htd);
  }

  @ClassRule
  public static ExternalResource connectionResource = new ExternalResource() {
    @Override
    protected void before() throws Throwable {
      if (useMiniCluster()) {
        testingUtility = new HBaseTestingUtility();
        testingUtility.startMiniCluster(1);
        configuration = testingUtility.getConfiguration();
      } else {
        configuration = BASE_CONFIGURATION;
      }
      createTable(TABLE_NAME);
    }

    @Override
    protected void after() {
      try {
        Admin admin = getAdmin();
        admin.disableTable(TABLE_NAME);
        admin.deleteTable(TABLE_NAME);
        if (useMiniCluster()) {
          testingUtility.shutdownMiniCluster();
        }
      } catch (Exception e) {
        // shutdownMiniCluster throws Exception, while getAdmin and others throw IOException.
        // Both result in the same desired outcome here.
        throw new RuntimeException("Error shutting down test cluster", e);
      }
    }
  };
}
