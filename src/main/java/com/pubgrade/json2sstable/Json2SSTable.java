package com.pubgrade.json2sstable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.CreateTableStatement;
import org.apache.cassandra.cql3.statements.ParsedStatement;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.service.ClientState;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

/**
 *
 * @author Dennis Birkholz <birkholz@pubgrade.com>
 */
public class Json2SSTable {
    public static void main(String[] args) throws SQLException, IOException, InvalidRequestException, ParseException {
        final String schema = FileUtils.readFileToString(new File(args[0]), "UTF-8");
        final String outputPath = args[1];
        
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
        
        final CQLSSTableWriter writer = CQLSSTableWriter
                .builder()
                .inDirectory(outputPath)
                .forTable(schema)
                .using(query)
                .build()
        ;
        
        final Scanner scanner = new Scanner(System.in, "UTF-8");
        
        long counter = 0;
        
        while (scanner.hasNextLine()) {
            counter++;
            final Object parsed = JSONValue.parseWithException(scanner.nextLine());
            
            if (!(parsed instanceof JSONObject)) {
                System.err.println("Parsed object is not json: " + parsed);
                continue;
            }
            
            final JSONObject json = (JSONObject)parsed;
            final Map<String,Object> row = new HashMap<>();
            
            for (final String columnName : columnNames) {
                row.put(columnName, json.get(columnName));
            }
            
            writer.addRow(row);
            
            if ((counter % 1000) == 0) {
                System.err.println("Imported " + counter + " rows.");
            }
        }
        
        writer.close();
    }
    
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}