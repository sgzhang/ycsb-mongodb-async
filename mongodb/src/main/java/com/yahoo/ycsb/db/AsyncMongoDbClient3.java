package com.yahoo.ycsb.db;

import com.mongodb.ConnectionString;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.client.*;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.yahoo.ycsb.*;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Created by sgzhang on Sep 25, 2017.
 * E-mail szhan45@lsu.edu.
 */
public class AsyncMongoDbClient3 extends DB{

  /** used to include a field in a response. */
  protected static final Integer INCLUDE = 1;

  /** the options to use for inserting many docuemtns. */
  private static final InsertManyOptions INSERT_UNORDERED =
      new InsertManyOptions().ordered(false);

  /** the options to use for inserting a single document. */
  private static final UpdateOptions UPDATE_WITH_UPSERT =
      new UpdateOptions().upsert(true);

  /** the database name to access. */
  private static String databaseName;

  /** the database to access. */
  private static MongoDatabase database;

  /**
   * count the number of times initialized to teardown on the last.
   * {@link #cleanup()}.
   */
  private static final AtomicInteger INIT_COUNT = new AtomicInteger(0);

  /** A singleton Mongo instance. */
  private static MongoClient mongoClient;

  /** the default read preference for the test. */
  private static ReadPreference readPreference;

  /** the default write concern for the test. */
  private static WriteConcern writeConcern;

  /** the batch size to use for inserts. */
  private static int batchSize;

  /** if true then use updates with the upsert option for inserts. */
  private static boolean useUpsert;

  /** the default inserts pending for the thread. */
  private final List<Document> bulkInserts = new ArrayList<Document>();



  @Override
  public void init() throws DBException {
    INIT_COUNT.incrementAndGet();
    synchronized (INCLUDE) {
      if (mongoClient != null) {
        return;
      }
      Properties props = getProperties();

      // set insert batchsize, default 1 - to be YCSB-original equivalent
      batchSize = Integer.parseInt(props.getProperty("batchsize", "1"));

      // set insert upserts, default to false
      useUpsert = Boolean.parseBoolean(props.getProperty("mongodb.upsert", "false"));

      // just use the standard connection format URL
      String url = props.getProperty("mongodb.url", null);
      boolean defaultURL = false;
      if (url == null) {
        defaultURL = true;
        url = "mongodb://localhost:27017/ycsb?w=1";
      }

      url = OptionsSupport.updateUrl(url, props);

      if (!url.startsWith("mongodb://")) {
        System.err.println("ERROR: Invalid URL: '" + url
            + "'. Must be of the form "
            + "'mongodb://<host1>:<port1>,<host2>:<port2>/database?options'. "
            + "http://docs.mongodb.org/manual/reference/connection-string/");
        System.exit(1);
      }

      try {
        final ConnectionString uri = new ConnectionString(url);
        String uriDB = uri.getDatabase();
        if (!defaultURL && (uriDB != null) &&
            !uriDB.isEmpty() && !"admin".endsWith(uriDB)) {
          databaseName = uriDB;
        } else {
          // if no database is specified in URI, use "ycsb"
          databaseName = "ycsb";
        }

        readPreference = uri.getReadPreference();
        writeConcern = uri.getWriteConcern();

        if (readPreference == null) {
          readPreference = ReadPreference.primaryPreferred();
        }
        if (writeConcern == null) {
          writeConcern = WriteConcern.W1;
        }
        System.out.println("test " + url);
        mongoClient = MongoClients.create(uri);
        database = mongoClient.getDatabase(databaseName)
            .withReadPreference(readPreference)
            .withWriteConcern(writeConcern);

        System.out.println("mongo client connection created with " + url);
      } catch (Exception e) {
        System.err.println("Could not initialize MongoDB connection pool for Loader: "
                            + e.toString());
        e.printStackTrace();
        return;
      }
    }
  }

  /**
   * cleanup any state for this DB, called once per DB instance, there is one DB.
   * instance per client thread.
   * @throws DBException
   */
  @Override
  public void cleanup() throws DBException {
    if(INIT_COUNT.decrementAndGet() == 0) {
      try {
        mongoClient.close();
      } catch (Exception e) {
        System.err.println("Could not close MongoDB connection pool: "
            + e.toString());
        e.printStackTrace();
      } finally {
        database = null;
        mongoClient = null;
      }
    }
  }

  /**
   * read a record from the database, each field/value pair from the result will
   * be stored in a HashMap.
   *
   * @param table The name of the table
   * @param key The record key of the record to read.
   * @param fields The list of fields to read, or null for all of them
   * @param result A HashMap of field/value pairs for the result
   * @return
   */
  @Override
  public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
    try {
      final MongoCollection<Document> collection = database.getCollection(table);
      final Document query = new Document("_id", key);
      final Document[] queryResult = new Document[1];
      final CountDownLatch countDownLatch = new CountDownLatch(1);

      FindIterable<Document> findIterable = collection.find(query);

      if (fields != null) {
        Document projection = new Document();
        for (String field : fields) {
          projection.put(field, INCLUDE);
        }
        findIterable.projection(projection);
      }

      findIterable.first(new SingleResultCallback<Document>() {
        @Override
        public void onResult(Document result, Throwable throwable) {
          queryResult[0] = result;
          countDownLatch.countDown();
        }
      });
      countDownLatch.await();

      if (queryResult[0].get("_id") != "") {
        fillMap(result, queryResult[0]);
      }
      return queryResult[0] != null ? Status.OK : Status.NOT_FOUND;
    } catch (Exception e) {
      System.err.println(e.toString());
      return Status.ERROR;
    }
  }

  /**
   * perform a scan for a set of records in the database, each field/value
   * pair from the result will be stored in a HashMap.
   *
   * @param table The name of the table
   * @param startKey
   * @param recordcount The number of records to read
   * @param fields The list of fields to read, or null for all of them
   * @param result A Vector of HashMaps, where each HashMap is a set field/value pairs for one record
   * @return
   */
  @Override
  public Status scan(String table, String startKey, int recordcount, Set<String> fields,
                     Vector<HashMap<String, ByteIterator>> result) {
    final List<Document> cursor = new ArrayList<>();
    try {
      final MongoCollection<Document> collection = database.getCollection(table);
      final CountDownLatch countDownLatch = new CountDownLatch(1);

      Document scanRange = new Document("$gte", startKey);
      Document query = new Document("_id", scanRange);
      Document sort = new Document("_id", INCLUDE);

      FindIterable<Document> findIterable =
          collection.find(query).sort(sort).limit(recordcount);

      if (fields != null) {
        Document projection = new Document();
        for (String field : fields) {
          projection.put(field, INCLUDE);
        }
        findIterable.projection(projection);
      }

      findIterable.into(cursor, new SingleResultCallback<List<Document>>() {
        @Override
        public void onResult(List<Document> documents, Throwable throwable) {
          countDownLatch.countDown();
        }
      });
      countDownLatch.await();

      if (cursor.size() == 0) {
        System.err.println("Nothing found in scan for key " + startKey);
        return Status.ERROR;
      }

      result.ensureCapacity(recordcount);

      int i = 0;
      while (i++ < cursor.size()) {
        HashMap<String, ByteIterator> resultMap =
            new HashMap<String, ByteIterator>();

        Document obj = cursor.get(i);
        fillMap(resultMap, obj);

        result.add(resultMap);
      }

      return Status.OK;
    } catch (Exception e) {
      System.err.println(e.toString());
      return Status.ERROR;
    } finally {
      if (cursor.size() > 0) {
        cursor.clear();
      }
    }
  }

  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    try {
      final MongoCollection<Document> collection = database.getCollection(table);
      final UpdateResult[] updateResultOut = new UpdateResult[1];
      final CountDownLatch countDownLatch = new CountDownLatch(1);

      Document query = new Document("_id", key);
      Document fieldToSet = new Document();
      for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
        fieldToSet.put(entry.getKey(), entry.getValue().toArray());
      }
      Document update = new Document("$set", fieldToSet);

      collection.updateOne(query, update,
          new SingleResultCallback<UpdateResult>() {
            @Override
            public void onResult(UpdateResult updateResult, Throwable throwable) {
              updateResultOut[0] = updateResult;
              countDownLatch.countDown();
            }
          });
      countDownLatch.await();

      if (updateResultOut[0].wasAcknowledged() && updateResultOut[0].getMatchedCount() == 0) {
        System.err.println("Nothing updated for key " + key);
        return Status.NOT_FOUND;
      }

      return Status.OK;
    } catch (Exception e) {
      System.err.println(e.toString());
      return Status.ERROR;
    }
  }

  /**
   * insert a record in the database, any field/value pairs in the specified
   * values HashMap will be written into the record with the specified record
   * key.
   *
   * @param table The name of the table
   * @param key The record key of the record to insert.
   * @param values A HashMap of field/value pairs to insert in the record
   * @return
   */
  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    try {
      final MongoCollection<Document> collection = database.getCollection(table);
      final Document toInsert = new Document("_id", key);
      final CountDownLatch countDownLatch = new CountDownLatch(1);
      for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
        toInsert.put(entry.getKey(), entry.getValue().toArray());
      }

      if (batchSize == 1) {
        if (useUpsert) {
          // this is effectively an insert, but using an upsert instead due
          // to current inability of the framework to clean up after itself
          // between test runs
          collection.replaceOne(new Document("_id", toInsert.get("_id")),
              toInsert, UPDATE_WITH_UPSERT, new SingleResultCallback<UpdateResult>() {
                @Override
                public void onResult(UpdateResult updateResult, Throwable throwable) {
                  countDownLatch.countDown();
                }
              });
          countDownLatch.await();
        } else {
          collection.insertOne(toInsert, new SingleResultCallback<Void>() {
            @Override
            public void onResult(Void aVoid, Throwable throwable) {
              countDownLatch.countDown();
            }
          });
          countDownLatch.await();
        }
      } else {
        bulkInserts.add(toInsert);
        if (bulkInserts.size() == batchSize) {
          if (useUpsert) {
            List<UpdateOneModel<Document>> updates =
                new ArrayList<UpdateOneModel<Document>>(bulkInserts.size());
            for (Document doc : bulkInserts) {
              updates.add(new UpdateOneModel<Document>(
                  new Document("_id", doc.get("_id")),
                  doc, UPDATE_WITH_UPSERT));
            }
            collection.bulkWrite(updates, new SingleResultCallback<BulkWriteResult>() {
              @Override
              public void onResult(BulkWriteResult bulkWriteResult, Throwable throwable) {
                countDownLatch.countDown();
              }
            });
            countDownLatch.await();
          } else {
            collection.insertMany(bulkInserts, INSERT_UNORDERED, new SingleResultCallback<Void>() {
              @Override
              public void onResult(Void aVoid, Throwable throwable) {
                countDownLatch.countDown();
              }
            });
            countDownLatch.await();
          }
          bulkInserts.clear();
        } else {
          return Status.BATCHED_OK;
        }
      }
      return Status.OK;
    } catch (Exception e) {
      System.err.println("Exception while trying bulk insert with");
      return Status.ERROR;
    }
  }

  /**
   * delete a record from the database.
   *
   * @param table The name of the table
   * @param key The record key of the record to delete.
   * @return
   */
  @Override
  public Status delete(final String table, final String key) {
    try {
      final CountDownLatch countDownLatch = new CountDownLatch(1);
      final DeleteResult[] deleteResultOut = new DeleteResult[1];
      final MongoCollection<Document> collection = database.getCollection(table);
      Document query = new Document("_id", key);

      collection.withWriteConcern(writeConcern).deleteOne(query, new SingleResultCallback<DeleteResult>() {
        @Override
        public void onResult(final DeleteResult deleteResult, Throwable throwable) {
          deleteResultOut[0] = deleteResult;
          countDownLatch.countDown();
        }
      });
      countDownLatch.await();
      if (deleteResultOut[0].getDeletedCount()==0 && deleteResultOut[0].wasAcknowledged()) {
        System.err.println("Nothing deleted for key " + key);
        return Status.NOT_FOUND;
      }
      return Status.OK;
    } catch (Exception e) {
      System.err.println(e.toString());
      return Status.ERROR;
    }
  }

  /**
   * fills the map with the values from the DBObject.
   *
   * @param resultMap
   * @param obj
   */
  protected void fillMap(Map<String, ByteIterator> resultMap, Document obj) {
    for (Map.Entry<String, Object> entry : obj.entrySet()) {
      if (entry.getValue() instanceof Binary) {
        resultMap.put(entry.getKey(),
            new ByteArrayByteIterator(((Binary) entry.getValue()).getData()));
      }
    }
  }
}
