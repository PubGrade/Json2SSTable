Json to Apache Cassandra SSTable converter
==========================================

The Json2SSTable tool speeds up the import of JSON data into Apache Cassandra.
It reads the JSON data from STDIN, one JSON object per line.
The attribute names of the JSON objects must exactly match the column names of the target Cassandra table.

For the Json2SSTable tool to work, the schema of the target table must be stored in a file as a CQL ``CREATE TABLE`` statement.
The schema can be obtained by running ``DESCRIBE TABLE xxx`` in the Cassandra shell (``cqlsh``).
The schema must hold the table name in the ``keyspace.table`` format.

The ``Json2SSTable.jar`` accepts three parameters:
1. A file containing the schema as a CQL ``CREATE TABLE statement``
2. The directory name to create the data in. The last two parts must be keyspace/table
3. Path to the cassandra config file (optional, defaults to /etc/cassandra/cassandra.yaml)

```Bash
# Create the SSTables in /output/path/keyspace/table
cat data.json |
    java -jar Json2SSTable.jar schema.cql /output/path/keyspace/table /path/to/cassandra.yaml

# Import the SSTables into Cassandra
sstableloader -d hostname /output/path/keyspace/table
```

FAQ
---

1. Why do I need to specify a cassandra.yaml file?

The file is required by the underlying ``CQLSSTableWriter` class so it must be provided.

Links
-----

DataStax [Using Cassandra Bulk Loader, Updated](http://www.datastax.com/dev/blog/using-the-cassandra-bulk-loader-updated) blog post.