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
package org.apache.hive.beeline;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.HiveMetaException;
import org.apache.hadoop.hive.metastore.MetaStoreSchemaInfo;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hive.beeline.HiveSchemaHelper.NestedScriptParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HiveSchemaTool {
  private String userName = null;
  private String passWord = null;
  private boolean dryRun = false;
  private boolean verbose = false;
  private String dbOpts = null;
  private final HiveConf hiveConf;
  private final String dbType;
  private final MetaStoreSchemaInfo metaStoreSchemaInfo;

  static final private Logger LOG = LoggerFactory.getLogger(HiveSchemaTool.class.getName());

  public HiveSchemaTool(String dbType) throws HiveMetaException {
    this(System.getenv("HIVE_HOME"), new HiveConf(HiveSchemaTool.class), dbType);
  }

  public HiveSchemaTool(String hiveHome, HiveConf hiveConf, String dbType)
      throws HiveMetaException {
    if (hiveHome == null || hiveHome.isEmpty()) {
      throw new HiveMetaException("No Hive home directory provided");
    }
    this.hiveConf = hiveConf;
    this.dbType = dbType;
    this.metaStoreSchemaInfo = new MetaStoreSchemaInfo(hiveHome, hiveConf, dbType);
    userName = hiveConf.get(ConfVars.METASTORE_CONNECTION_USER_NAME.varname);
    try {
      passWord = ShimLoader.getHadoopShims().getPassword(hiveConf,
          HiveConf.ConfVars.METASTOREPWD.varname);
    } catch (IOException err) {
      throw new HiveMetaException("Error getting metastore password", err);
    }
  }

  public HiveConf getHiveConf() {
    return hiveConf;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public void setPassWord(String passWord) {
    this.passWord = passWord;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  public void setDbOpts(String dbOpts) {
    this.dbOpts = dbOpts;
  }

  private static void printAndExit(Options cmdLineOptions) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("schemaTool", cmdLineOptions);
    System.exit(1);
  }

  Connection getConnectionToMetastore(boolean printInfo)
      throws HiveMetaException {
    return HiveSchemaHelper.getConnectionToMetastore(userName,
        passWord, printInfo, hiveConf);
  }

  private NestedScriptParser getDbCommandParser(String dbType) {
    return HiveSchemaHelper.getDbCommandParser(dbType, dbOpts, userName,
        passWord, hiveConf);
  }

  /***
   * Print Hive version and schema version
   * @throws MetaException
   */
  public void showInfo() throws HiveMetaException {
    Connection metastoreConn = getConnectionToMetastore(true);
    String hiveVersion = MetaStoreSchemaInfo.getHiveSchemaVersion();
    String dbVersion = getMetaStoreSchemaVersion(metastoreConn);
    System.out.println("Hive distribution version:\t " + hiveVersion);
    System.out.println("Metastore schema version:\t " + dbVersion);
    assertCompatibleVersion(hiveVersion, dbVersion);

  }

  private String getMetaStoreSchemaVersion(Connection metastoreConn)
      throws HiveMetaException {
    return getMetaStoreSchemaVersion(metastoreConn, false);
  }

  // read schema version from metastore
  private String getMetaStoreSchemaVersion(Connection metastoreConn,
      boolean checkDuplicatedVersion) throws HiveMetaException {
    String versionQuery;
    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      versionQuery = "select t.\"SCHEMA_VERSION\" from \"VERSION\" t";
    } else {
      versionQuery = "select t.SCHEMA_VERSION from VERSION t";
    }
    try(Statement stmt = metastoreConn.createStatement();
        ResultSet res = stmt.executeQuery(versionQuery)) {
      if (!res.next()) {
        throw new HiveMetaException("Didn't find version data in metastore");
      }
      String currentSchemaVersion = res.getString(1);
      if (checkDuplicatedVersion && res.next()) {
        throw new HiveMetaException("Multiple versions were found in metastore.");
      }
      return currentSchemaVersion;
    } catch (SQLException e) {
      throw new HiveMetaException("Failed to get schema version.", e);
    }
  }

  boolean validateLocations(Connection conn, String defaultLocPrefix) throws HiveMetaException {
    System.out.println("Validating database/table/partition locations");
    boolean rtn;
    rtn = checkMetaStoreDBLocation(conn, defaultLocPrefix);
    rtn = checkMetaStoreTableLocation(conn, defaultLocPrefix) && rtn;
    rtn = checkMetaStorePartitionLocation(conn, defaultLocPrefix) && rtn;
    System.out.println((rtn ? "Succeeded" : "Failed") + " in database/table/partition location validation");
    return rtn;
  }

  private String getNameOrID(ResultSet res, int nameInx, int idInx) throws SQLException {
    String itemName = res.getString(nameInx);
    return  (itemName == null || itemName.isEmpty()) ? "ID: " + res.getString(idInx) : "Name: " + itemName;
  }

  // read schema version from metastore
  private boolean checkMetaStoreDBLocation(Connection conn, String locHeader)
      throws HiveMetaException {
    String defaultPrefix = locHeader;
    String dbLoc;
    boolean isValid = true;
    int numOfInvalid = 0;
    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      dbLoc = "select dbt.\"DB_ID\", dbt.\"NAME\", dbt.\"DB_LOCATION_URI\" from \"DBS\" dbt";
    } else {
      dbLoc = "select dbt.DB_ID, dbt.NAME, dbt.DB_LOCATION_URI from DBS dbt";
    }

    try(Statement stmt = conn.createStatement();
        ResultSet res = stmt.executeQuery(dbLoc)) {
      while (res.next()) {
        String locValue = res.getString(3);
        if (locValue == null) {
          System.err.println("NULL Location for DB with " + getNameOrID(res,2,1));
          numOfInvalid++;
        } else {
          URI currentUri = null;
          try {
            currentUri = new Path(locValue).toUri();
          } catch (Exception pe) {
            System.err.println("Invalid Location for DB with " + getNameOrID(res,2,1));
            System.err.println(pe.getMessage());
            numOfInvalid++;
            continue;
          }
          
          if (currentUri.getScheme() == null || currentUri.getScheme().isEmpty()) {
            System.err.println("Missing Location scheme for DB with " + getNameOrID(res,2,1));
            System.err.println("The Location is: " + locValue);
            numOfInvalid++;
          } else if (defaultPrefix != null && !defaultPrefix.isEmpty() && locValue.substring(0,defaultPrefix.length())
              .compareToIgnoreCase(defaultPrefix) != 0) {
            System.err.println("Mismatch root Location for DB with " + getNameOrID(res,2,1));
            System.err.println("The Location is: " + locValue);
            numOfInvalid++;
          }
        }
      }

    } catch (SQLException e) {
      throw new HiveMetaException("Failed to get DB Location Info.", e);
    }
    if (numOfInvalid > 0) {
      isValid = false;
      System.err.println("Total number of invalid DB locations is: "+ numOfInvalid);
    }
    return isValid;
  }

  private boolean checkMetaStoreTableLocation(Connection conn, String locHeader)
      throws HiveMetaException {
    String defaultPrefix = locHeader;
    String tabLoc, tabIDRange;
    boolean isValid = true;
    int numOfInvalid = 0;
    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      tabIDRange = "select max(\"TBL_ID\"), min(\"TBL_ID\") from \"TBLS\" ";
    } else {
      tabIDRange = "select max(TBL_ID), min(TBL_ID) from TBLS";
    }

    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      tabLoc = "select tbl.\"TBL_ID\", tbl.\"TBL_NAME\", sd.\"LOCATION\", dbt.\"DB_ID\", dbt.\"NAME\" from \"TBLS\" tbl inner join " +
    "\"SDS\" sd on tbl.\"SD_ID\" = sd.\"SD_ID\" and tbl.\"TBL_TYPE\" != '" + TableType.VIRTUAL_VIEW +
    "' and tbl.\"TBL_ID\" >= ? and tbl.\"TBL_ID\"<= ? " + "inner join \"DBS\" dbt on tbl.\"DB_ID\" = dbt.\"DB_ID\" ";
    } else {
      tabLoc = "select tbl.TBL_ID, tbl.TBL_NAME, sd.LOCATION, dbt.DB_ID, dbt.NAME from TBLS tbl join SDS sd on tbl.SD_ID = sd.SD_ID and tbl.TBL_TYPE !='"
      + TableType.VIRTUAL_VIEW + "' and tbl.TBL_ID >= ? and tbl.TBL_ID <= ?  inner join DBS dbt on tbl.DB_ID = dbt.DB_ID";
    }

    long maxID = 0, minID = 0;
    long rtnSize = 2000;

    try {
      Statement stmt = conn.createStatement();
      ResultSet res = stmt.executeQuery(tabIDRange);
      if (res.next()) {
        maxID = res.getLong(1);
        minID = res.getLong(2);
      }
      res.close();
      stmt.close();
      PreparedStatement pStmt = conn.prepareStatement(tabLoc);
      while (minID <= maxID) {
        pStmt.setLong(1, minID);
        pStmt.setLong(2, minID + rtnSize);
        res = pStmt.executeQuery();
        while (res.next()) {
          String locValue = res.getString(3);
          if (locValue == null) {
            System.err.println("In DB with " + getNameOrID(res,5,4));
            System.err.println("NULL Location for TABLE with " + getNameOrID(res,2,1));
            numOfInvalid++;
          } else {
            URI currentUri = null;
            try {
              currentUri = new Path(locValue).toUri();
            } catch (Exception pe) {
              System.err.println("In DB with " + getNameOrID(res,5,4));
              System.err.println("Invalid location for Table with " + getNameOrID(res,2,1));
              System.err.println(pe.getMessage());
              numOfInvalid++;
              continue;
            }
            if (currentUri.getScheme() == null || currentUri.getScheme().isEmpty()) {
              System.err.println("In DB with " + getNameOrID(res,5,4));
              System.err.println("Missing Location scheme for Table with " + getNameOrID(res,2,1));
              System.err.println("The Location is: " + locValue);
              numOfInvalid++;
            } else if(defaultPrefix != null && !defaultPrefix.isEmpty() && locValue.substring(0,defaultPrefix.length())
                .compareToIgnoreCase(defaultPrefix) != 0) {
              System.err.println("In DB with " + getNameOrID(res,5,4));
              System.err.println("Mismatch root Location for Table with " + getNameOrID(res,2,1));
              System.err.println("The Location is: " + locValue);
              numOfInvalid++;
            }
          }
        }
        res.close();
        minID += rtnSize + 1;

      }
      pStmt.close();

    } catch (SQLException e) {
      throw new HiveMetaException("Failed to get Table Location Info.", e);
    }
    if (numOfInvalid > 0) {
      isValid = false;
      System.err.println("Total number of invalid TABLE locations is: "+ numOfInvalid);
    }
    return isValid;
  }

  private boolean checkMetaStorePartitionLocation(Connection conn, String locHeader)
      throws HiveMetaException {
    String defaultPrefix = locHeader;
    String partLoc, partIDRange;
    boolean isValid = true;
    int numOfInvalid = 0;
    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      partIDRange = "select max(\"PART_ID\"), min(\"PART_ID\") from \"PARTITIONS\" ";
    } else {
      partIDRange = "select max(PART_ID), min(PART_ID) from PARTITIONS";
    }

    if (getDbCommandParser(dbType).needsQuotedIdentifier()) {
      partLoc = "select pt.\"PART_ID\", pt.\"PART_NAME\", sd.\"LOCATION\", tbl.\"TBL_ID\", tbl.\"TBL_NAME\",dbt.\"DB_ID\", dbt.\"NAME\" from \"PARTITIONS\" pt "
           + "inner join \"SDS\" sd on pt.\"SD_ID\" = sd.\"SD_ID\" and pt.\"PART_ID\" >= ? and pt.\"PART_ID\"<= ? "
           + " inner join \"TBLS\" tbl on pt.\"TBL_ID\" = tbl.\"TBL_ID\" inner join "
           + "\"DBS\" dbt on tbl.\"DB_ID\" = dbt.\"DB_ID\" ";
    } else {
      partLoc = "select pt.PART_ID, pt.PART_NAME, sd.LOCATION, tbl.TBL_ID, tbl.TBL_NAME, dbt.DB_ID, dbt.NAME from PARTITIONS pt "
          + "inner join SDS sd on pt.SD_ID = sd.SD_ID and pt.PART_ID >= ? and pt.PART_ID <= ?  "
          + "inner join TBLS tbl on tbl.TBL_ID = pt.TBL_ID inner join DBS dbt on tbl.DB_ID = dbt.DB_ID ";
    }

    long maxID = 0, minID = 0;
    long rtnSize = 2000;

    try {
      Statement stmt = conn.createStatement();
      ResultSet res = stmt.executeQuery(partIDRange);
      if (res.next()) {
        maxID = res.getLong(1);
        minID = res.getLong(2);
      }
      res.close();
      stmt.close();
      PreparedStatement pStmt = conn.prepareStatement(partLoc);
      while (minID <= maxID) {
        pStmt.setLong(1, minID);
        pStmt.setLong(2, minID + rtnSize);
        res = pStmt.executeQuery();
        while (res.next()) {
          String locValue = res.getString(3);
          if (locValue == null) {
            System.err.println("In DB with " + getNameOrID(res,7,6) + ", TABLE with " + getNameOrID(res,5,4));
            System.err.println("NULL Location for PARTITION with " + getNameOrID(res,2,1));
            numOfInvalid++;
          } else {
            URI currentUri = null;
            try {
              currentUri = new Path(locValue).toUri();
            } catch (Exception pe) {
              System.err.println("In DB with " + getNameOrID(res,7,6) + ", TABLE with " + getNameOrID(res,5,4));
              System.err.println("Invalid location for PARTITON with " + getNameOrID(res,2,1));
              System.err.println(pe.getMessage());
              numOfInvalid++;
              continue;
            }
            if (currentUri.getScheme() == null || currentUri.getScheme().isEmpty()) {
              System.err.println("In DB with " + getNameOrID(res,7,6) + ", TABLE with " + getNameOrID(res,5,4));
              System.err.println("Missing Location scheme for PARTITON with " + getNameOrID(res,2,1));
              System.err.println("The Location is: " + locValue);
              numOfInvalid++;
            } else if (defaultPrefix != null && !defaultPrefix.isEmpty() && locValue.substring(0,defaultPrefix.length())
                .compareToIgnoreCase(defaultPrefix) != 0) {
              System.err.println("In DB with " + getNameOrID(res,7,6) + ", TABLE with " + getNameOrID(res,5,4));
              System.err.println("Mismatch root Location for PARTITON with " + getNameOrID(res,2,1));
              System.err.println("The Location is: " + locValue);
              numOfInvalid++;
            }
          }
        }
        res.close();
        minID += rtnSize + 1;
      }
      pStmt.close();
    } catch (SQLException e) {
      throw new HiveMetaException("Failed to get Partiton Location Info.", e);
    }
    if (numOfInvalid > 0) {
      isValid = false;
      System.err.println("Total number of invalid PARTITION locations is: "+ numOfInvalid);
    }
    return isValid;
  }

  // test the connection metastore using the config property
  private void testConnectionToMetastore() throws HiveMetaException {
    Connection conn = getConnectionToMetastore(true);
    try {
      conn.close();
    } catch (SQLException e) {
      throw new HiveMetaException("Failed to close metastore connection", e);
    }
  }


  /**
   * check if the current schema version in metastore matches the Hive version
   * @throws MetaException
   */
  public void verifySchemaVersion() throws HiveMetaException {
    // don't check version if its a dry run
    if (dryRun) {
      return;
    }
    String newSchemaVersion = getMetaStoreSchemaVersion(
        getConnectionToMetastore(false));
    // verify that the new version is added to schema
    assertCompatibleVersion(MetaStoreSchemaInfo.getHiveSchemaVersion(), newSchemaVersion);

  }

  private void assertCompatibleVersion(String hiveSchemaVersion, String dbSchemaVersion)
      throws HiveMetaException {
    if (!MetaStoreSchemaInfo.isVersionCompatible(hiveSchemaVersion, dbSchemaVersion)) {
      throw new HiveMetaException("Metastore schema version is not compatible. Hive Version: "
          + hiveSchemaVersion + ", Database Schema Version: " + dbSchemaVersion);
    }
  }

  /**
   * Perform metastore schema upgrade. extract the current schema version from metastore
   * @throws MetaException
   */
  public void doUpgrade() throws HiveMetaException {
    String fromVersion = getMetaStoreSchemaVersion(
        getConnectionToMetastore(false));
    if (fromVersion == null || fromVersion.isEmpty()) {
      throw new HiveMetaException("Schema version not stored in the metastore. " +
          "Metastore schema is too old or corrupt. Try specifying the version manually");
    }
    doUpgrade(fromVersion);
  }

  /**
   * Perform metastore schema upgrade
   *
   * @param fromSchemaVer
   *          Existing version of the metastore. If null, then read from the metastore
   * @throws MetaException
   */
  public void doUpgrade(String fromSchemaVer) throws HiveMetaException {
    if (MetaStoreSchemaInfo.getHiveSchemaVersion().equals(fromSchemaVer)) {
      System.out.println("No schema upgrade required from version " + fromSchemaVer);
      return;
    }
    // Find the list of scripts to execute for this upgrade
    List<String> upgradeScripts =
        metaStoreSchemaInfo.getUpgradeScripts(fromSchemaVer);
    testConnectionToMetastore();
    System.out.println("Starting upgrade metastore schema from version " +
        fromSchemaVer + " to " + MetaStoreSchemaInfo.getHiveSchemaVersion());
    String scriptDir = metaStoreSchemaInfo.getMetaStoreScriptDir();
    try {
      for (String scriptFile : upgradeScripts) {
        System.out.println("Upgrade script " + scriptFile);
        if (!dryRun) {
          runPreUpgrade(scriptDir, scriptFile);
          runBeeLine(scriptDir, scriptFile);
          System.out.println("Completed " + scriptFile);
        }
      }
    } catch (IOException eIO) {
      throw new HiveMetaException(
          "Upgrade FAILED! Metastore state would be inconsistent !!", eIO);
    }

    // Revalidated the new version after upgrade
    verifySchemaVersion();
  }

  /**
   * Initialize the metastore schema to current version
   *
   * @throws MetaException
   */
  public void doInit() throws HiveMetaException {
    doInit(MetaStoreSchemaInfo.getHiveSchemaVersion());

    // Revalidated the new version after upgrade
    verifySchemaVersion();
  }

  /**
   * Initialize the metastore schema
   *
   * @param toVersion
   *          If null then current hive version is used
   * @throws MetaException
   */
  public void doInit(String toVersion) throws HiveMetaException {
    testConnectionToMetastore();
    System.out.println("Starting metastore schema initialization to " + toVersion);

    String initScriptDir = metaStoreSchemaInfo.getMetaStoreScriptDir();
    String initScriptFile = metaStoreSchemaInfo.generateInitFileName(toVersion);

    try {
      System.out.println("Initialization script " + initScriptFile);
      if (!dryRun) {
        runBeeLine(initScriptDir, initScriptFile);
        System.out.println("Initialization script completed");
      }
    } catch (IOException e) {
      throw new HiveMetaException("Schema initialization FAILED!" +
          " Metastore state would be inconsistent !!", e);
    }
  }

  public void doValidate() throws HiveMetaException {
    System.out.println("Starting metastore validation");
    Connection conn = getConnectionToMetastore(false);
    try {
      validateSchemaVersions(conn);
      validateSequences(conn);
      validateSchemaTables(conn);
      validateLocations(conn, null);
      validateColumnNullValues(conn);
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException e) {
          throw new HiveMetaException("Failed to close metastore connection", e);
        }
      }
    }

    System.out.println("Done with metastore validation");
  }

  boolean validateSequences(Connection conn) throws HiveMetaException {
    Map<String, Pair<String, String>> seqNameToTable =
        new ImmutableMap.Builder<String, Pair<String, String>>()
        .put("MDatabase", Pair.of("DBS", "DB_ID"))
        .put("MRole", Pair.of("ROLES", "ROLE_ID"))
        .put("MGlobalPrivilege", Pair.of("GLOBAL_PRIVS", "USER_GRANT_ID"))
        .put("MTable", Pair.of("TBLS","TBL_ID"))
        .put("MStorageDescriptor", Pair.of("SDS", "SD_ID"))
        .put("MSerDeInfo", Pair.of("SERDES", "SERDE_ID"))
        .put("MColumnDescriptor", Pair.of("CDS", "CD_ID"))
        .put("MTablePrivilege", Pair.of("TBL_PRIVS", "TBL_GRANT_ID"))
        .put("MTableColumnStatistics", Pair.of("TAB_COL_STATS", "CS_ID"))
        .put("MPartition", Pair.of("PARTITIONS", "PART_ID"))
        .put("MPartitionColumnStatistics", Pair.of("PART_COL_STATS", "CS_ID"))
        .put("MFunction", Pair.of("FUNCS", "FUNC_ID"))
        .put("MIndex", Pair.of("IDXS", "INDEX_ID"))
        .put("MStringList", Pair.of("SKEWED_STRING_LIST", "STRING_LIST_ID"))
        .build();

    System.out.println("Validating sequence number for SEQUENCE_TABLE");

    boolean isValid = true;
    try {
      Statement stmt = conn.createStatement();
      for (String seqName : seqNameToTable.keySet()) {
        String tableName = seqNameToTable.get(seqName).getLeft();
        String tableKey = seqNameToTable.get(seqName).getRight();
        String seqQuery = getDbCommandParser(dbType).needsQuotedIdentifier() ?
            ("select t.\"NEXT_VAL\" from \"SEQUENCE_TABLE\" t WHERE t.\"SEQUENCE_NAME\"='org.apache.hadoop.hive.metastore.model." + seqName + "'")
            : ("select t.NEXT_VAL from SEQUENCE_TABLE t WHERE t.SEQUENCE_NAME='org.apache.hadoop.hive.metastore.model." + seqName + "'");
        String maxIdQuery = getDbCommandParser(dbType).needsQuotedIdentifier() ?
            ("select max(\"" + tableKey + "\") from \"" + tableName + "\"")
            : ("select max(" + tableKey + ") from " + tableName);

          ResultSet res = stmt.executeQuery(maxIdQuery);
          if (res.next()) {
             long maxId = res.getLong(1);
             if (maxId > 0) {
               ResultSet resSeq = stmt.executeQuery(seqQuery);
               if (!resSeq.next() || resSeq.getLong(1) < maxId) {
                 isValid = false;
                 System.err.println("Incorrect sequence number: table - " + tableName);
               }
             }
          }
      }

      System.out.println((isValid ? "Succeeded" :"Failed") + " in sequence number validation for SEQUENCE_TABLE");
      return isValid;
    } catch(SQLException e) {
        throw new HiveMetaException("Failed to validate sequence number for SEQUENCE_TABLE", e);
    }
  }

  boolean validateSchemaVersions(Connection conn) throws HiveMetaException {
    System.out.println("Validating schema version");
    try {
      String newSchemaVersion = getMetaStoreSchemaVersion(conn, true);
      assertCompatibleVersion(MetaStoreSchemaInfo.getHiveSchemaVersion(), newSchemaVersion);
    } catch (HiveMetaException hme) {
      if (hme.getMessage().contains("Metastore schema version is not compatible")
        || hme.getMessage().contains("Multiple versions were found in metastore")
        || hme.getMessage().contains("Didn't find version data in metastore")) {
        System.out.println("Failed in schema version validation: " + hme.getMessage());
          return false;
        } else {
          throw hme;
        }
    }
    System.out.println("Succeeded in schema version validation.");
    return true;
  }

  boolean validateSchemaTables(Connection conn) throws HiveMetaException {
    ResultSet rs              = null;
    DatabaseMetaData metadata = null;
    List<String> dbTables     = new ArrayList<String>();
    List<String> schemaTables = new ArrayList<String>();
    List<String> subScripts   = new ArrayList<String>();
    String version            = getMetaStoreSchemaVersion(conn);

    System.out.println("Validating tables in the schema for version " + version);
    try {
      metadata       = conn.getMetaData();
      String[] types = {"TABLE"};
      rs             = metadata.getTables(null, null, "%", types);
      String table   = null;

      while (rs.next()) {
        table = rs.getString("TABLE_NAME");
        dbTables.add(table.toLowerCase());
        LOG.debug("Found table " + table + " in HMS dbstore");
      }
    } catch (SQLException e) {
      throw new HiveMetaException(e);
    } finally {
      if (rs != null) {
        try {
          rs.close();
        } catch (SQLException e) {
          throw new HiveMetaException("Failed to close resultset", e);
        }
      }
    }

    // parse the schema file to determine the tables that are expected to exist
    // we are using oracle schema because it is simpler to parse, no quotes or backticks etc
    String baseDir    = new File(metaStoreSchemaInfo.getMetaStoreScriptDir()).getParent();
    String schemaFile = baseDir + "/oracle/hive-schema-" + version + ".oracle.sql";

    try {
      LOG.debug("Parsing schema script " + schemaFile);
      subScripts.addAll(findCreateTable(schemaFile, schemaTables));
      while (subScripts.size() > 0) {
        schemaFile = baseDir + "/oracle/" + subScripts.remove(0);
        LOG.debug("Parsing subscript " + schemaFile);
        subScripts.addAll(findCreateTable(schemaFile, schemaTables));
      }
    } catch (Exception e) {
      return false;
    }

    System.out.println("Expected (from schema definition) " + schemaTables.size() +
        " tables, Found (from HMS metastore) " + dbTables.size() + " tables");

    // now diff the lists
    schemaTables.removeAll(dbTables);
    if (schemaTables.size() > 0) {
      System.out.println(schemaTables.size() + " tables [ " + Arrays.toString(schemaTables.toArray())
          + " ] are missing from the database schema.");
      return false;
    } else {
      System.out.println("Succeeded in schema table validation");
      return true;
    }
  }

  private List<String> findCreateTable(String path, List<String> tableList) {
    Matcher matcher                       = null;
    String line                           = null;
    List<String> subs                     = new ArrayList<String>();
    final String NESTED_SCRIPT_IDENTIFIER = "@";
    Pattern regexp                        = Pattern.compile("(CREATE TABLE(IF NOT EXISTS)*) (\\S+).*");

    try (
      BufferedReader reader = new BufferedReader(new FileReader(path));
    ){
      while ((line = reader.readLine()) != null) {
        if (line.startsWith(NESTED_SCRIPT_IDENTIFIER)) {
          int endIndex = (line.indexOf(";") > -1 ) ? line.indexOf(";") : line.length();
          // remove the trailing SEMI-COLON if any
          subs.add(line.substring(NESTED_SCRIPT_IDENTIFIER.length(), endIndex));
          continue;
        }
        matcher = regexp.matcher(line);
        if (matcher.find()) {
          String table = matcher.group(3);
          tableList.add(table.toLowerCase());
          LOG.debug("Found table " + table + " in the schema");
        }
      }
    } catch (IOException ex){
      ex.printStackTrace();
    }

    return subs;
  }

  boolean validateColumnNullValues(Connection conn) throws HiveMetaException {
    System.out.println("Validating columns for incorrect NULL values");
    boolean isValid = true;
    try {
      Statement stmt = conn.createStatement();
      String tblQuery = getDbCommandParser(dbType).needsQuotedIdentifier() ?
          ("select t.* from \"TBLS\" t WHERE t.\"SD_ID\" IS NULL and (t.\"TBL_TYPE\"='" + TableType.EXTERNAL_TABLE + "' or t.\"TBL_TYPE\"='" + TableType.MANAGED_TABLE + "')")
          : ("select t.* from TBLS t WHERE t.SD_ID IS NULL and (t.TBL_TYPE='" + TableType.EXTERNAL_TABLE + "' or t.TBL_TYPE='" + TableType.MANAGED_TABLE + "')");

      ResultSet res = stmt.executeQuery(tblQuery);
      while (res.next()) {
         long tableId = res.getLong("TBL_ID");
         String tableName = res.getString("TBL_NAME");
         String tableType = res.getString("TBL_TYPE");
         isValid = false;
         System.err.println("Value of SD_ID in TBLS should not be NULL: hive table - " + tableName + " tableId - " + tableId + " tableType - " + tableType);
      }

      System.out.println((isValid ? "Succeeded" : "Failed") + " in column validation for incorrect NULL values");
      return isValid;
    } catch(SQLException e) {
        throw new HiveMetaException("Failed to validate columns for incorrect NULL values", e);
    }
  }

  /**
   *  Run pre-upgrade scripts corresponding to a given upgrade script,
   *  if any exist. The errors from pre-upgrade are ignored.
   *  Pre-upgrade scripts typically contain setup statements which
   *  may fail on some database versions and failure is ignorable.
   *
   *  @param scriptDir upgrade script directory name
   *  @param scriptFile upgrade script file name
   */
  private void runPreUpgrade(String scriptDir, String scriptFile) {
    for (int i = 0;; i++) {
      String preUpgradeScript =
          MetaStoreSchemaInfo.getPreUpgradeScriptName(i, scriptFile);
      File preUpgradeScriptFile = new File(scriptDir, preUpgradeScript);
      if (!preUpgradeScriptFile.isFile()) {
        break;
      }

      try {
        runBeeLine(scriptDir, preUpgradeScript);
        System.out.println("Completed " + preUpgradeScript);
      } catch (Exception e) {
        // Ignore the pre-upgrade script errors
        System.err.println("Warning in pre-upgrade script " + preUpgradeScript + ": "
            + e.getMessage());
        if (verbose) {
          e.printStackTrace();
        }
      }
    }
  }

  /***
   * Run beeline with the given metastore script. Flatten the nested scripts
   * into single file.
   */
  private void runBeeLine(String scriptDir, String scriptFile)
      throws IOException, HiveMetaException {
    NestedScriptParser dbCommandParser = getDbCommandParser(dbType);
    // expand the nested script
    String sqlCommands = dbCommandParser.buildCommand(scriptDir, scriptFile);
    File tmpFile = File.createTempFile("schematool", ".sql");
    tmpFile.deleteOnExit();

    // write out the buffer into a file. Add beeline commands for autocommit and close
    FileWriter fstream = new FileWriter(tmpFile.getPath());
    BufferedWriter out = new BufferedWriter(fstream);
    out.write("!autocommit on" + System.getProperty("line.separator"));
    out.write(sqlCommands);
    out.write("!closeall" + System.getProperty("line.separator"));
    out.close();
    runBeeLine(tmpFile.getPath());
  }

  // Generate the beeline args per hive conf and execute the given script
  public void runBeeLine(String sqlScriptFile) throws IOException {
    List<String> argList = new ArrayList<String>();
    argList.add("-u");
    argList.add(HiveSchemaHelper.getValidConfVar(
        ConfVars.METASTORECONNECTURLKEY, hiveConf));
    argList.add("-d");
    argList.add(HiveSchemaHelper.getValidConfVar(
        ConfVars.METASTORE_CONNECTION_DRIVER, hiveConf));
    argList.add("-n");
    argList.add(userName);
    argList.add("-p");
    argList.add(passWord);
    argList.add("-f");
    argList.add(sqlScriptFile);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Going to invoke file that contains:");
      BufferedReader reader = new BufferedReader(new FileReader(sqlScriptFile));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          LOG.debug("script: " + line);
        }
      } finally {
        if (reader != null) {
          reader.close();
        }
      }
    }

    // run the script using Beeline
    BeeLine beeLine = new BeeLine();
    try {
      if (!verbose) {
        beeLine.setOutputStream(new PrintStream(new NullOutputStream()));
        beeLine.getOpts().setSilent(true);
      }
      beeLine.getOpts().setAllowMultiLineCommand(false);
      beeLine.getOpts().setIsolation("TRANSACTION_READ_COMMITTED");
      // We can be pretty sure that an entire line can be processed as a single command since
      // we always add a line separator at the end while calling dbCommandParser.buildCommand.
      beeLine.getOpts().setEntireLineAsCommand(true);
      LOG.debug("Going to run command <" + StringUtils.join(argList, " ") + ">");
      int status = beeLine.begin(argList.toArray(new String[0]), null);
      if (status != 0) {
        throw new IOException("Schema script failed, errorcode " + status);
      }
    } finally {
      beeLine.close();
    }
  }

  // Create the required command line options
  @SuppressWarnings("static-access")
  private static void initOptions(Options cmdLineOptions) {
    Option help = new Option("help", "print this message");
    Option upgradeOpt = new Option("upgradeSchema", "Schema upgrade");
    Option upgradeFromOpt = OptionBuilder.withArgName("upgradeFrom").hasArg().
                withDescription("Schema upgrade from a version").
                create("upgradeSchemaFrom");
    Option initOpt = new Option("initSchema", "Schema initialization");
    Option initToOpt = OptionBuilder.withArgName("initTo").hasArg().
                withDescription("Schema initialization to a version").
                create("initSchemaTo");
    Option infoOpt = new Option("info", "Show config and schema details");
    Option validateOpt = new Option("validate", "Validate the database");

    OptionGroup optGroup = new OptionGroup();
    optGroup.addOption(upgradeOpt).addOption(initOpt).
                addOption(help).addOption(upgradeFromOpt).
                addOption(initToOpt).addOption(infoOpt).addOption(validateOpt);
    optGroup.setRequired(true);

    Option userNameOpt = OptionBuilder.withArgName("user")
                .hasArgs()
                .withDescription("Override config file user name")
                .create("userName");
    Option passwdOpt = OptionBuilder.withArgName("password")
                .hasArgs()
                 .withDescription("Override config file password")
                 .create("passWord");
    Option dbTypeOpt = OptionBuilder.withArgName("databaseType")
                .hasArgs().withDescription("Metastore database type")
                .create("dbType");
    Option dbOpts = OptionBuilder.withArgName("databaseOpts")
                .hasArgs().withDescription("Backend DB specific options")
                .create("dbOpts");
    Option dryRunOpt = new Option("dryRun", "list SQL scripts (no execute)");
    Option verboseOpt = new Option("verbose", "only print SQL statements");

    cmdLineOptions.addOption(help);
    cmdLineOptions.addOption(dryRunOpt);
    cmdLineOptions.addOption(userNameOpt);
    cmdLineOptions.addOption(passwdOpt);
    cmdLineOptions.addOption(dbTypeOpt);
    cmdLineOptions.addOption(verboseOpt);
    cmdLineOptions.addOption(dbOpts);
    cmdLineOptions.addOptionGroup(optGroup);
  }

  public static void main(String[] args) {
    CommandLineParser parser = new GnuParser();
    CommandLine line = null;
    String dbType = null;
    String schemaVer = null;
    Options cmdLineOptions = new Options();

    // Argument handling
    initOptions(cmdLineOptions);
    try {
      line = parser.parse(cmdLineOptions, args);
    } catch (ParseException e) {
      System.err.println("HiveSchemaTool:Parsing failed.  Reason: " + e.getLocalizedMessage());
      printAndExit(cmdLineOptions);
    }

    if (line.hasOption("help")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("schemaTool", cmdLineOptions);
      return;
    }

    if (line.hasOption("dbType")) {
      dbType = line.getOptionValue("dbType");
      if ((!dbType.equalsIgnoreCase(HiveSchemaHelper.DB_DERBY) &&
          !dbType.equalsIgnoreCase(HiveSchemaHelper.DB_MSSQL) &&
          !dbType.equalsIgnoreCase(HiveSchemaHelper.DB_MYSQL) &&
          !dbType.equalsIgnoreCase(HiveSchemaHelper.DB_POSTGRACE) && !dbType
          .equalsIgnoreCase(HiveSchemaHelper.DB_ORACLE))) {
        System.err.println("Unsupported dbType " + dbType);
        printAndExit(cmdLineOptions);
      }
    } else {
      System.err.println("no dbType supplied");
      printAndExit(cmdLineOptions);
    }

    System.setProperty(HiveConf.ConfVars.METASTORE_SCHEMA_VERIFICATION.varname, "true");
    try {
      HiveSchemaTool schemaTool = new HiveSchemaTool(dbType);

      if (line.hasOption("userName")) {
        schemaTool.setUserName(line.getOptionValue("userName"));
      }
      if (line.hasOption("passWord")) {
        schemaTool.setPassWord(line.getOptionValue("passWord"));
      }
      if (line.hasOption("dryRun")) {
        schemaTool.setDryRun(true);
      }
      if (line.hasOption("verbose")) {
        schemaTool.setVerbose(true);
      }
      if (line.hasOption("dbOpts")) {
        schemaTool.setDbOpts(line.getOptionValue("dbOpts"));
      }
      if (line.hasOption("info")) {
        schemaTool.showInfo();
      } else if (line.hasOption("upgradeSchema")) {
        schemaTool.doUpgrade();
      } else if (line.hasOption("upgradeSchemaFrom")) {
        schemaVer = line.getOptionValue("upgradeSchemaFrom");
        schemaTool.doUpgrade(schemaVer);
      } else if (line.hasOption("initSchema")) {
        schemaTool.doInit();
      } else if (line.hasOption("initSchemaTo")) {
        schemaVer = line.getOptionValue("initSchemaTo");
        schemaTool.doInit(schemaVer);
      } else if (line.hasOption("validate")) {
        schemaTool.doValidate();
      } else {
        System.err.println("no valid option supplied");
        printAndExit(cmdLineOptions);
      }
    } catch (HiveMetaException e) {
      System.err.println(e);
      if (e.getCause() != null) {
        Throwable t = e.getCause();
        System.err.println("Underlying cause: "
            + t.getClass().getName() + " : "
            + t.getMessage());
        if (e.getCause() instanceof SQLException) {
          System.err.println("SQL Error code: " + ((SQLException)t).getErrorCode());
        }
      }
      if (line.hasOption("verbose")) {
        e.printStackTrace();
      } else {
        System.err.println("Use --verbose for detailed stacktrace.");
      }
      System.err.println("*** schemaTool failed ***");
      System.exit(1);
    }
    System.out.println("schemaTool completed");

  }
}
