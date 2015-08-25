/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.destination.hive;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.ErrorCode;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactoryBuilder;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.UnknownDBException;
import org.apache.hadoop.hive.metastore.api.UnknownTableException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.hive.hcatalog.streaming.HiveEndPoint;
import org.apache.hive.hcatalog.streaming.RecordWriter;
import org.apache.hive.hcatalog.streaming.StreamingConnection;
import org.apache.hive.hcatalog.streaming.StreamingException;
import org.apache.hive.hcatalog.streaming.StrictJsonWriter;
import org.apache.hive.hcatalog.streaming.TransactionBatch;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Writes to Hive using Hive's Streaming API.
 * Currently tables must be backed by ORC file to use this API.
 */
public class HiveTarget extends BaseTarget {
  private static final Logger LOG = LoggerFactory.getLogger(HiveTarget.class);
  private static final String SDC_FIELD_SEP = "/";
  private static final String HIVE_METASTORE_URI = "hive.metastore.uris";

  private final String hiveThriftUrl;
  private final String schema;
  private final String tableName;
  private final String hiveConfDir;
  private final boolean autoCreatePartitions;
  private final int txnBatchSize;
  private final int bufferLimit;
  private final List<FieldMappingConfig> columnMappings;
  private final Map<String, String> additionalHiveProperties;

  private Map<String, String> columnsToFields;
  private Map<String, String> partitionsToFields;
  private HiveConf hiveConf;
  private UserGroupInformation loginUgi;
  private DataGeneratorFactory dataGeneratorFactory;

  private LoadingCache<HiveEndPoint, StreamingConnection> hiveConnectionPool;
  private LoadingCache<HiveEndPoint, RecordWriter> recordWriterPool;

  class HiveConnectionLoader extends CacheLoader<HiveEndPoint, StreamingConnection> {
    @Override
    public StreamingConnection load(HiveEndPoint endPoint) throws StageException {
      StreamingConnection connection;
      try {
         connection = endPoint.newConnection(autoCreatePartitions, hiveConf, loginUgi);
      } catch (StreamingException | InterruptedException e) {
        throw new StageException(Errors.HIVE_09, e.toString(), e);
      }
      return connection;
    }
  }

  class HiveConnectionRemovalListener implements RemovalListener<HiveEndPoint, StreamingConnection> {
    @Override
    public void onRemoval(RemovalNotification<HiveEndPoint, StreamingConnection> notification) {
      LOG.debug("Evicting StreamingConnection from pool: {}", notification);
      StreamingConnection connection = notification.getValue();
      if (null != connection) {
        connection.close();
      }
    }
  }

  class HiveRecordWriterLoader extends CacheLoader<HiveEndPoint, RecordWriter> {
    @Override
    public RecordWriter load(HiveEndPoint endPoint) throws Exception {
      return new StrictJsonWriter(endPoint, hiveConf);
    }
  }

  public HiveTarget(
      String hiveThriftUrl,
      String schema,
      String tableName,
      String hiveConfDir,
      List<FieldMappingConfig> columnMappings,
      boolean autoCreatePartitions,
      int txnBatchSize,
      int bufferLimitKb,
      Map<String, String> additionalHiveProperties
  ) {
    this.hiveThriftUrl = hiveThriftUrl;
    this.schema = schema;
    this.tableName = tableName;
    this.hiveConfDir = hiveConfDir;
    this.columnMappings = columnMappings;
    this.autoCreatePartitions = autoCreatePartitions;
    this.txnBatchSize = txnBatchSize;
    this.additionalHiveProperties = additionalHiveProperties;
    bufferLimit = 1000 * bufferLimitKb;
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();

    partitionsToFields = new HashMap<>();
    columnsToFields = new HashMap<>();

    hiveConf = new HiveConf();
    if (null != hiveConfDir && !hiveConfDir.isEmpty()) {
      File hiveConfDir = new File(this.hiveConfDir);

      if (!hiveConfDir.isAbsolute()) {
        hiveConfDir = new File(getContext().getResourcesDirectory(), this.hiveConfDir).getAbsoluteFile();
      }

      if (hiveConfDir.exists()) {
        File coreSite = new File(hiveConfDir.getAbsolutePath(), "core-site.xml");
        File hiveSite = new File(hiveConfDir.getAbsolutePath(), "hive-site.xml");
        File hdfsSite = new File(hiveConfDir.getAbsolutePath(), "hdfs-site.xml");

        if (!coreSite.exists()) {
          issues.add(getContext().createConfigIssue(
                  Groups.HIVE.name(),
                  "hiveConfDir",
                  Errors.HIVE_06,
                  coreSite.getName(),
                  this.hiveConfDir)
          );
        } else {
          hiveConf.addResource(new Path(coreSite.getAbsolutePath()));
        }

        if (!hdfsSite.exists()) {
          issues.add(getContext().createConfigIssue(
                  Groups.HIVE.name(),
                  "hiveConfDir",
                  Errors.HIVE_06,
                  hdfsSite.getName(),
                  this.hiveConfDir)
          );
        } else {
          hiveConf.addResource(new Path(hdfsSite.getAbsolutePath()));
        }

        if (!hiveSite.exists()) {
          issues.add(getContext().createConfigIssue(
                  Groups.HIVE.name(),
                  "hiveConfDir",
                  Errors.HIVE_06,
                  hiveSite.getName(),
                  this.hiveConfDir)
          );
        } else {
          hiveConf.addResource(new Path(hiveSite.getAbsolutePath()));
        }
      } else {
        issues.add(getContext().createConfigIssue(Groups.HIVE.name(), "hiveConfDir", Errors.HIVE_07, this.hiveConfDir));
      }
    } else if (hiveThriftUrl == null || hiveThriftUrl.isEmpty()) {
      issues.add(getContext().createConfigIssue(Groups.HIVE.name(), "hiveThriftUrl", Errors.HIVE_13));
    }

    // Specified URL overrides what's in the Hive Conf
    hiveConf.set(HIVE_METASTORE_URI, hiveThriftUrl);
    // Add any additional hive conf overrides
    for (Map.Entry<String, String> entry : additionalHiveProperties.entrySet()) {
      hiveConf.set(entry.getKey(), entry.getValue());
    }

    try {
      // forcing UGI to initialize with the security settings from the stage
      UserGroupInformation.setConfiguration(hiveConf);
      Subject subject = Subject.getSubject(AccessController.getContext());
      if (UserGroupInformation.isSecurityEnabled()) {
        loginUgi = UserGroupInformation.getUGIFromSubject(subject);
      } else {
        UserGroupInformation.loginUserFromSubject(subject);
        loginUgi = UserGroupInformation.getLoginUser();
      }
      LOG.info("Subject = {}, Principals = {}, Login UGI = {}", subject,
        subject == null ? "null" : subject.getPrincipals(), loginUgi);
      // Proxy users are not currently supported due to: https://issues.apache.org/jira/browse/HIVE-11089
    } catch (IOException e) {
      issues.add(getContext().createConfigIssue(Groups.HIVE.name(), null, Errors.HIVE_11, e.getMessage()));
    }

    try {
      issues.addAll(loginUgi.doAs(
          new PrivilegedExceptionAction<List<ConfigIssue>>() {
            @Override
            public List<ConfigIssue> run() {
              List<ConfigIssue> issues = new ArrayList<>();
              HiveMetaStoreClient client = null;
              try {
                client = new HiveMetaStoreClient(hiveConf);

                List<FieldSchema> columnNames = client.getFields(schema, tableName);
                for (FieldSchema field : columnNames) {
                  columnsToFields.put(field.getName(), SDC_FIELD_SEP + field.getName());
                }

                Table table = client.getTable(schema, tableName);
                List<FieldSchema> partitionKeys = table.getPartitionKeys();
                for (FieldSchema field : partitionKeys) {
                  partitionsToFields.put(field.getName(), SDC_FIELD_SEP + field.getName());
                }
              } catch (UnknownDBException e) {
                issues.add(getContext().createConfigIssue(Groups.HIVE.name(), "schema", Errors.HIVE_02, schema));
              } catch (UnknownTableException e) {
                issues.add(
                    getContext().createConfigIssue(Groups.HIVE.name(), "table", Errors.HIVE_03, schema, tableName)
                );
              } catch (MetaException e) {
                issues.add(
                    getContext().createConfigIssue(Groups.HIVE.name(), "hiveUrl", Errors.HIVE_05, e.getMessage())
                );
              } catch (TException e) {
                issues.add(
                    getContext().createConfigIssue(Groups.HIVE.name(), "hiveUrl", Errors.HIVE_04, e.getMessage())
                );
              } finally {
                if (null != client) {
                  client.close();
                }
              }
              return issues;
            }
          }
      ));
    } catch (Error | IOException | InterruptedException e) {
      LOG.error("Received unknown error in validation: {}", e.toString(), e);
      issues.add(getContext().createConfigIssue(Groups.HIVE.name(), "", Errors.HIVE_01, e.toString()));
    } catch (UndeclaredThrowableException e) {
      LOG.error("Received unknown error in validation: {}", e.toString(), e);
      issues.add(
          getContext().createConfigIssue(Groups.HIVE.name(), "", Errors.HIVE_01, e.getUndeclaredThrowable().toString())
      );
    }

    // Now apply any custom mappings
    if (validColumnMappings(issues)) {
      for (FieldMappingConfig mapping : columnMappings) {
        LOG.debug("Custom mapping field {} to column {}", mapping.field, mapping.columnName);
        if (columnsToFields.containsKey(mapping.columnName)) {
          LOG.debug("Mapping field {} to column {}", mapping.field, mapping.columnName);
          columnsToFields.put(mapping.columnName, mapping.field);
        } else if (partitionsToFields.containsKey(mapping.columnName)) {
          LOG.debug("Mapping field {} to partition {}", mapping.field, mapping.columnName);
          partitionsToFields.put(mapping.columnName, mapping.field);
        }
      }
    }

    dataGeneratorFactory = createDataGeneratorFactory();

    // Note that cleanup is done synchronously by default while servicing .get
    hiveConnectionPool = CacheBuilder.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .removalListener(new HiveConnectionRemovalListener())
        .build(new HiveConnectionLoader());

    recordWriterPool = CacheBuilder.newBuilder()
        .maximumSize(10)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build(new HiveRecordWriterLoader());

    LOG.debug("Total issues: {}", issues.size());
    return issues;
  }

  private boolean validColumnMappings(List<ConfigIssue> issues) {
    boolean isValid = true;
    Set<String> columns = new HashSet<>(issues.size());
    for (FieldMappingConfig mapping : columnMappings) {
      if (!columns.add(mapping.columnName)) {
        isValid = false;
        issues.add(
            getContext().createConfigIssue(Groups.HIVE.name(), "columnMappings", Errors.HIVE_00, mapping.columnName)
        );
      }
    }
    return isValid;
  }

  @Override
  public void destroy() {
    for (Map.Entry<HiveEndPoint, StreamingConnection> entry : hiveConnectionPool.asMap().entrySet()) {
      entry.getValue().close();
    }
    super.destroy();
  }

  private TransactionBatch getBatch(int batchSize, HiveEndPoint endPoint) throws InterruptedException,
      StreamingException, ExecutionException {
    return hiveConnectionPool.get(endPoint).fetchTransactionBatch(batchSize, recordWriterPool.get(endPoint));
  }

  private DataGeneratorFactory createDataGeneratorFactory() {
    DataGeneratorFactoryBuilder builder = new DataGeneratorFactoryBuilder(
        getContext(),
        DataFormat.JSON.getGeneratorFormat()
    );
    return builder
        .setCharset(StandardCharsets.UTF_8) // Only UTF-8 is supported.
        .setMode(JsonMode.MULTIPLE_OBJECTS)
        .build();
  }

  @Override
  public void write(Batch batch) throws StageException {
    Map<HiveEndPoint, TransactionBatch> transactionBatches = new HashMap<>();
    Iterator<Record> it = batch.getRecords();

    while (it.hasNext()) {
      Record record = it.next();

      // Check that record has all required fields (partition columns).
      List<String> missingPartitions = getMissingRequiredFields(record, partitionsToFields);

      if (missingPartitions.size() == 0) {
        try {
          HiveEndPoint endPoint = getEndPointForRecord(record);

          TransactionBatch hiveBatch = transactionBatches.get(endPoint);
          if (null == hiveBatch || 0 == hiveBatch.remainingTransactions()) {
            hiveBatch = getBatch(txnBatchSize, endPoint);
            transactionBatches.put(endPoint, hiveBatch);
          }

          hiveBatch.beginNextTransaction();

          ByteArrayOutputStream bytes = new ByteArrayOutputStream(bufferLimit);
          DataGenerator generator = dataGeneratorFactory.getGenerator(bytes);

          // Transform record for field mapping overrides
          applyCustomMappings(record);

          // Remove Partition fields
          for (String fieldPath : partitionsToFields.values()) {
            record.delete(fieldPath);
          }
          generator.write(record);
          generator.close();

          hiveBatch.write(bytes.toByteArray());
          hiveBatch.commit();
        } catch (InterruptedException | StreamingException | IOException e) {
          LOG.error("Error processing batch: {}", e.toString(), e);
          throw new StageException(Errors.HIVE_01, e.toString(), e);
        } catch (ExecutionException e) {
          LOG.error("Error processing batch: {}", e.getCause().toString(), e);
          throw new StageException(Errors.HIVE_01, e.getCause().toString(), e);
        } catch (OnRecordErrorException e) {
          handleError(record, e.getErrorCode(), e.getParams());
        }
      } else {
        if (missingPartitions.size() != 0) {
          handleError(record, Errors.HIVE_08, StringUtils.join(",", missingPartitions));
        }
      }
    }

    for (TransactionBatch transactionBatch : transactionBatches.values()) {
      try {
        transactionBatch.close();
      } catch (InterruptedException | StreamingException e) {
        LOG.error("Failed to close transaction batch: {}", e.toString(), e);
      }
    }
  }

  private void applyCustomMappings(Record record) {
    for (Map.Entry<String, String> entry : columnsToFields.entrySet()) {
      if (!entry.getValue().equals(SDC_FIELD_SEP + entry.getKey())) {
        // This is a custom mapping
        if (record.has(entry.getValue())) {
          // The record has the requested field, rename it to match the column name.
          record.set(SDC_FIELD_SEP + entry.getKey(), record.get(entry.getValue()));
          // Remove the original field
          record.delete(entry.getKey());
        }
      }
    }
  }

  private List<String> getMissingRequiredFields(Record record, Map<String, String> mappings) {
    List<String> missingFields = new ArrayList<>(mappings.size());
    for (Map.Entry<String, String> mapping : mappings.entrySet()) {
      if (!record.has(mapping.getValue())) {
        missingFields.add(mapping.getValue());
      }
    }
    return missingFields;
  }

  private HiveEndPoint getEndPointForRecord(final Record record) throws OnRecordErrorException {
    List<String> partitions = new ArrayList<>(partitionsToFields.size());
    for (String partitionField : partitionsToFields.values()) {
      if (record.has(partitionField)) {
        partitions.add(record.get(partitionField).getValueAsString());
      }
    }
    HiveEndPoint endPoint;
    try {
      endPoint = new HiveEndPoint(hiveThriftUrl, schema, tableName, partitions);
    } catch (IllegalArgumentException e) {
      throw new OnRecordErrorException(Errors.HIVE_12, e.toString(), e);
    }
    return endPoint;
  }

  private void handleError(Record record, ErrorCode errorCode, Object... params) throws StageException {
    switch (getContext().getOnErrorRecord()) {
      case DISCARD:
        break;
      case TO_ERROR:
        getContext().toError(record, errorCode, params);
        break;
      case STOP_PIPELINE:
        throw new StageException(errorCode, params);
      default:
        throw new IllegalStateException(Utils.format("It should never happen. OnError '{}'",
            getContext().getOnErrorRecord()));
    }
  }
}
