package com.pubgrade.json2sstable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.CreateTableStatement;
import org.apache.cassandra.cql3.statements.ParsedStatement;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.ListType;
import org.apache.cassandra.db.marshal.ReversedType;
import org.apache.cassandra.db.marshal.SetType;
import org.apache.cassandra.db.marshal.TimestampType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.service.ClientState;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

/**
 * This executable class reads one Json object per line from STDIN and creates SSTable data from it that can be imported into Apache Cassandra.
 * 
 * @author Dennis Birkholz <birkholz@pubgrade.com>
 */
public class Json2SSTable {
    public static void main(String[] args) throws SQLException, IOException, InvalidRequestException, ParseException {
        final String schema = FileUtils.readFileToString(new File(args[0]), "UTF-8");
        final File outputPath = new File(args[1]);
        outputPath.mkdirs();
        System.err.println("Writing SSTables to '" + outputPath.getAbsolutePath() + "'");
        
        // Set cassandra configuration file
        if (args.length >= 3) {
            final File cassandraConfig = new File(args[2]);
            if (!cassandraConfig.exists()) {
                throw new IllegalArgumentException("The supplied Cassandra configuration file '" + args[2] + "' can not be found or is not readable!");
            }
            System.setProperty("cassandra.config", cassandraConfig.toURI().toString());
        } else if (System.getProperty("cassandra.config") == null) {
            System.setProperty("cassandra.config", "file:///etc/cassandra/cassandra.yaml");
        }
        System.err.println("Using cassandra config file '" + System.getProperty("cassandra.config") + "'");
        
        final CFMetaData tableMetaData = Json2SSTable.parseSchema(schema);
        final List<String> columnNames = new ArrayList<>();
        
        for (final ColumnDefinition column : tableMetaData.allColumns()) {
            columnNames.add(column.name.toString());
        }
        
        final String query = "INSERT INTO "
                + tableMetaData.ksName + "." + tableMetaData.cfName
                + " (" + StringUtils.join(columnNames,", ") + ")"
                + " VALUES (?" + StringUtils.repeat(",?", columnNames.size()-1) + ");"
        ;
        
        long counter = 0;
        
        try (CQLSSTableWriter writer = CQLSSTableWriter
                .builder()
                .inDirectory(outputPath)
                .forTable(schema)
                .using(query)
                .build()) {
            final Scanner scanner = new Scanner(System.in, "UTF-8");
            
            while (scanner.hasNextLine()) {
                counter++;
                final Object parsed = JSONValue.parseWithException(scanner.nextLine());
                
                if (!(parsed instanceof JSONObject)) {
                    System.err.println("Parsed object is not json: " + parsed);
                    continue;
                }
                
                final JSONObject json = (JSONObject)parsed;
                final Map<String,Object> row = new HashMap<>();
                
                for (final ColumnDefinition column : tableMetaData.allColumns()) {
                    final String columnName = column.name.toString();
                    row.put(columnName, convertObject(column.type, json.get(columnName)));
                }
                
                writer.addRow(row);
                
                if ((counter % 1000) == 0) {
                    System.err.print("\rImported " + counter + " rows...");
                }
            }
        }
        
        System.err.println("\rFinished importing " + counter + " rows.");
    }
    
    /**
     * Maps objects returned by SimpleJson to objects usable for the given Cassandra column type.
     * 
     * @param type The Cassandra marshalling type of the column
     * @param original Column value object as created by SimpleJSON
     * @return Column value converted to a class usable for the column type
     */
    private static Object convertObject(final AbstractType type, final Object original) {
        if ((original instanceof JSONArray) && ((JSONArray)original).isEmpty()) {
            return null;
        }
        
        else if (type instanceof ReversedType) {
            return convertObject(((ReversedType)type).baseType, original);
        }
        
        else if ((type instanceof Int32Type) && (original instanceof Long)) {
            return ((Long)original).intValue();
        }
        
        else if ((type instanceof TimestampType) && (original instanceof Long)) {
            return new Date((Long)original);
        }

        else if ((type instanceof SetType) && (original instanceof JSONArray)) {
            final AbstractType elementType = ((SetType)type).getElementsType();
            final Set r = new HashSet(); 
            
            for (final Object element : (JSONArray)original) {
                r.add(convertObject(elementType, element));
            }
            
            return r;
        }

        else if ((type instanceof ListType) && (original instanceof JSONArray)) {
            final AbstractType elementType = ((ListType)type).getElementsType();
            final List r = new ArrayList(); 
            
            for (final Object element : (JSONArray)original) {
                r.add(convertObject(elementType, element));
            }
            
            return r;
        }
        
        return original;
    }
    
    /**
     * Get the meta data (column definitions) for the target table.
     * 
     * @param query The CREATE TABLE statement for the table the Json data should be put into.
     * @return The table's meta data
     */
    private static CFMetaData parseSchema(String query) {
        try {
            ClientState state = ClientState.forInternalCalls();
            ParsedStatement.Prepared prepared = QueryProcessor.getStatement(query, state);
            CQLStatement stmt = prepared.statement;
            stmt.validate(state);

            if (!stmt.getClass().equals(CreateTableStatement.class)) {
                throw new IllegalArgumentException("Invalid query, must be a CREATE TABLE statement");
            }

            return CreateTableStatement.class.cast(stmt).getCFMetaData();
        } catch (RequestValidationException | IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }
}
