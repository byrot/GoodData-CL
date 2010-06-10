package org.gooddata.transformation.executor;

import com.gooddata.exceptions.ModelException;
import com.gooddata.integration.model.Column;
import com.gooddata.integration.model.DLIPart;
import com.gooddata.modeling.model.SourceColumn;
import com.gooddata.transformation.executor.model.PdmColumn;
import com.gooddata.transformation.executor.model.PdmLookupReplication;
import com.gooddata.transformation.executor.model.PdmSchema;
import com.gooddata.transformation.executor.model.PdmTable;
import com.gooddata.util.JdbcUtil;
import com.gooddata.util.StringUtil;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * GoodData abstract SQL executor. Generates the DDL (tables and indexes), DML (transformation SQL) and other
 * SQL statements necessary for the data normalization (lookup generation)
 * @author zd <zd@gooddata.com>
 * @version 1.0
 */
public abstract class AbstractSqlExecutor implements SqlExecutor {

    private static Logger l = Logger.getLogger(AbstractSqlExecutor.class);

    // autoincrement syntax
    protected String SYNTAX_AUTOINCREMENT = "";

    // SQL concat function prefix and suffix
    protected String SYNTAX_CONCAT_FUNCTION_PREFIX = "";
    protected String SYNTAX_CONCAT_FUNCTION_SUFFIX = "";
    protected String SYNTAX_CONCAT_OPERATOR = "";

    // separates the different LABELs when we concatenate them to create an unique identifier out of them
    protected String HASH_SEPARATOR = "%";


    /**
     * Executes the system DDL initialization
     * @param c JDBC connection
     * @throws ModelException if there is a problem with the PDM schema (e.g. multiple source or fact tables)
     * @throws SQLException in case of db problems
     */
    public void executeSystemDdlSql(Connection c) throws ModelException, SQLException {
        createSnapshotTable(c);
    }

    /**
     * Executes the DDL initialization
     * @param c JDBC connection
     * @param schema the PDM schema
     * @throws ModelException if there is a problem with the PDM schema (e.g. multiple source or fact tables)
     * @throws SQLException in case of db problems
     */
    public void executeDdlSql(Connection c, PdmSchema schema) throws ModelException, SQLException {
        for(PdmTable table : schema.getTables()) {
            createTable(c, table);
            if(PdmTable.PDM_TABLE_TYPE_SOURCE.equals(table.getType()))
                indexAllTableColumns(c, table);
        }
        JdbcUtil.executeUpdate(c,
            "INSERT INTO snapshots(name,firstid,lastid,tmstmp) VALUES ('" + schema.getFactTable().getName() + "',0,0,0)"
        );
    }

    /**
     * Executes the data normalization script
     * @param c JDBC connection
     * @param schema the PDM schema
     * @throws com.gooddata.exceptions.ModelException if there is a problem with the PDM schema
     * (e.g. multiple source or fact tables)
     * @throws SQLException in case of db problems
     */
    public void executeNormalizeSql(Connection c, PdmSchema schema) throws ModelException, SQLException {

        //populate REFERENCEs lookups from the referenced lookups
        executeLookupReplicationSql(c, schema);

        populateLookupTables(c, schema);
        populateConnectionPointTables(c, schema);
        // nothing for the reference columns

        insertSnapshotsRecord(c, schema);
        insertFactsToFactTable(c, schema);

        for(PdmTable tbl : schema.getLookupTables())
            updateForeignKeyInFactTable(c, tbl, schema);
        for(PdmTable tbl : schema.getReferenceTables())
            updateForeignKeyInFactTable(c, tbl, schema);

        updateSnapshotsRecord(c, schema);
    }

    /**
     * Executes the copying of the referenced lookup tables
     * @param c JDBC connection
     * @param schema the PDM schema
     * @throws com.gooddata.exceptions.ModelException if there is a problem with the PDM schema (e.g. multiple source or fact tables)
     * @throws java.sql.SQLException in case of db problems
     */
    public void executeLookupReplicationSql(Connection c, PdmSchema schema) throws ModelException, SQLException {
        for (PdmLookupReplication lr : schema.getLookupReplications()) {
            JdbcUtil.executeUpdate(c,
                "DELETE FROM " + lr.getReferencingLookup()
            );
            JdbcUtil.executeUpdate(c,
                "INSERT INTO " + lr.getReferencingLookup() + "(id," + lr.getReferencingColumn() +",hashid)" +
                " SELECT id," + lr.getReferencedColumn() + "," + lr.getReferencedColumn() + " FROM " + lr.getReferencedLookup()
            );
        }
    }

    protected void indexAllTableColumns(Connection c, PdmTable table) throws SQLException {
        for( PdmColumn column : table.getColumns()) {
            if(!column.isPrimaryKey() && !column.isUnique())
                JdbcUtil.executeUpdate(c,"CREATE INDEX idx_" + table.getName() + "_" + column.getName() + " ON " +
                              table.getName() + "("+column.getName()+")");
        }
    }

    protected void createTable(Connection c, PdmTable table) throws SQLException {
        String pk = "";
        String sql = "CREATE TABLE " + table.getName() + " (\n";
        for( PdmColumn column : table.getColumns()) {
            sql += " "+ column.getName() + " " + column.getType();
            if(column.isUnique())
                sql += " UNIQUE";
            if(column.isAutoIncrement())
                sql += " " + SYNTAX_AUTOINCREMENT;
            if(column.isPrimaryKey())
                if(pk != null && pk.length() > 0)
                    pk += "," + column.getName();
                else
                    pk += column.getName();
            sql += ",";
        }
        sql += " PRIMARY KEY (" + pk + "))";

        JdbcUtil.executeUpdate(c, sql);
    }

    protected void createSnapshotTable(Connection c) throws SQLException {
        JdbcUtil.executeUpdate(c,
            "CREATE TABLE snapshots (" +
                " id INT " + SYNTAX_AUTOINCREMENT + "," +
                " name VARCHAR(255)," +
                " tmstmp BIGINT," +
                " firstid INT," +
                " lastid INT," +
                " PRIMARY KEY (id)" +
                ")"
        );
    }

    protected void insertSnapshotsRecord(Connection c, PdmSchema schema) throws ModelException, SQLException {
        PdmTable factTable = schema.getFactTable();
        String fact = factTable.getName();
        Date dt = new Date();
        JdbcUtil.executeUpdate(c,
            "INSERT INTO snapshots(name,tmstmp,firstid) SELECT '"+fact+"',"+dt.getTime()+",MAX(id)+1 FROM " + fact
        );
        // compensate for the fact that MAX returns NULL when there are no rows in the SELECT
        JdbcUtil.executeUpdate(c,
            "UPDATE snapshots SET firstid = 0 WHERE name = '"+fact+"' AND firstid IS NULL"
        );
    }

    protected void updateSnapshotsRecord(Connection c, PdmSchema schema) throws ModelException, SQLException {
        PdmTable factTable = schema.getFactTable();
        String fact = factTable.getName();
        JdbcUtil.executeUpdate(c,
            "UPDATE snapshots SET lastid = (SELECT MAX(id) FROM " + factTable.getName() + ") WHERE name = '" +
            factTable.getName() + "' AND lastid IS NULL"
        );
        // compensate for the fact that MAX returns NULL when there are no rows in the SELECT
        JdbcUtil.executeUpdate(c,
            "UPDATE snapshots SET lastid = 0 WHERE name = '" + factTable.getName() + "' AND lastid IS NULL"
        );
    }

    protected abstract void insertFactsToFactTable(Connection c, PdmSchema schema) throws ModelException, SQLException;

    protected void populateLookupTables(Connection c, PdmSchema schema) throws ModelException, SQLException {
        for(PdmTable lookupTable : schema.getLookupTables()) {
            populateLookupTable(c, lookupTable, schema);
        }
    }

    protected void populateConnectionPointTables(Connection c, PdmSchema schema) throws SQLException, ModelException {
        for(PdmTable cpTable : schema.getConnectionPointTables())
            populateConnectionPointTable(c, cpTable, schema);
    }

    protected void updateForeignKeyInFactTable(Connection c, PdmTable lookupTable, PdmSchema schema)
            throws ModelException, SQLException {
        String lookup = lookupTable.getName();
        String fact = schema.getFactTable().getName();
        String source = schema.getSourceTable().getName();
        String associatedSourceColumns = concatAssociatedSourceColumns(lookupTable);
        JdbcUtil.executeUpdate(c,
              "UPDATE " + fact + " SET  " + lookupTable.getAssociatedSourceColumn() + "_id = (SELECT id FROM " +
              lookup + " d," + source + " o WHERE " + associatedSourceColumns + " = d.hashid AND o.o_genid = " +
              fact + ".id) WHERE id > (SELECT MAX(lastid) FROM snapshots WHERE name = '" + fact+"')"
        );
    }

    protected void populateLookupTable(Connection c, PdmTable lookupTable, PdmSchema schema)
            throws ModelException, SQLException {
        String lookup = lookupTable.getName();
        String fact = schema.getFactTable().getName();
        String source = schema.getSourceTable().getName();
        String insertColumns = "hashid," + getInsertColumns(lookupTable);
        String associatedSourceColumns = getAssociatedSourceColumns(lookupTable);
        // add the concatenated columns that fills the hashid to the beginning
        String nestedSelectColumns = concatAssociatedSourceColumns(lookupTable) + associatedSourceColumns;
        JdbcUtil.executeUpdate(c,
            "INSERT INTO " + lookup + "(" + insertColumns +
            ") SELECT DISTINCT " + nestedSelectColumns + " FROM " + source +
            " WHERE o_genid > (SELECT MAX(lastid) FROM snapshots WHERE name='" + fact +
            "') AND " + associatedSourceColumns + " NOT IN (SELECT hashid FROM " +
            lookupTable.getName() + ")"
        );
    }

    protected void populateConnectionPointTable(Connection c, PdmTable lookupTable, PdmSchema schema)
            throws ModelException, SQLException {
        String lookup = lookupTable.getName();
        String fact = schema.getFactTable().getName();
        String source = schema.getSourceTable().getName();
        String insertColumns = "id,hashid," + getInsertColumns(lookupTable);
        String associatedSourceColumns = getAssociatedSourceColumns(lookupTable);
        // add the concatenated columns that fills the hashid to the beginning
        String nestedSelectColumns = "o_genid," + concatAssociatedSourceColumns(lookupTable) + associatedSourceColumns;
        /*
        JdbcUtil.executeUpdate(c,
            "INSERT INTO " + lookup + "(" + insertColumns + ") SELECT DISTINCT " + nestedSelectColumns +
            " FROM " + source + " WHERE o_genid > (SELECT MAX(lastid) FROM snapshots WHERE name='" + fact +
            "') AND " + associatedSourceColumns + " NOT IN (SELECT hashid FROM " + lookup + ")"
        );
        */
        // TODO: when snapshotting, there are duplicate CONNECTION POINT VALUES
        // we need to decide if we want to accumultae the connection point lookup or not
        /*
        JdbcUtil.executeUpdate(c,
            "INSERT INTO " + lookup + "(" + insertColumns + ") SELECT DISTINCT " + nestedSelectColumns +
            " FROM " + source + " WHERE o_genid > (SELECT MAX(lastid) FROM snapshots WHERE name='" + fact +"')"
        );
        */
        JdbcUtil.executeUpdate(c,
            "INSERT INTO " + lookup + "(" + insertColumns +
            ") SELECT DISTINCT " + nestedSelectColumns + " FROM " + source +
            " WHERE o_genid > (SELECT MAX(lastid) FROM snapshots WHERE name='" + fact +
            "') AND " + associatedSourceColumns + " NOT IN (SELECT hashid FROM " +
            lookupTable.getName() + ")"
        );
    }

    protected String concatAssociatedSourceColumns(PdmTable lookupTable) {
        String associatedColumns = SYNTAX_CONCAT_FUNCTION_PREFIX;
        for(PdmColumn column : lookupTable.getAssociatedColumns()) {
            // if there are LABELS, the lookup can't be added twice to the FROM clause
            if(associatedColumns.length() > 0)
                associatedColumns += SYNTAX_CONCAT_OPERATOR +  column.getSourceColumn();
            else
                associatedColumns = column.getSourceColumn();
        }
        associatedColumns += SYNTAX_CONCAT_FUNCTION_SUFFIX;
        return associatedColumns;
    }

    protected String getInsertColumns(PdmTable lookupTable) {
        String insertColumns = "";
        for(PdmColumn column : lookupTable.getAssociatedColumns()) {
            if(insertColumns.length() > 0)
                insertColumns += "," + column.getName();
            else
                insertColumns += column.getName();
        }
        return insertColumns;
    }

    protected String getAssociatedSourceColumns(PdmTable lookupTable) {
        String sourceColumns = "";
        for(PdmColumn column : lookupTable.getAssociatedColumns()) {
            if(sourceColumns.length() > 0)
                sourceColumns += "," + column.getSourceColumn();
            else
                sourceColumns += column.getSourceColumn();
        }
        return sourceColumns;
    }

    protected String getNonAutoincrementColumns(PdmTable tbl) {
        String cols = "";
        for (PdmColumn col : tbl.getColumns()) {
            String cn = col.getName();
            if(!col.isAutoIncrement())
                if (cols != null && cols.length() > 0)
                    cols += "," + cn;
                else
                    cols += cn;
        }
        return cols;
    }

    protected String getLoadWhereClause(DLIPart part, PdmSchema schema, int[] snapshotIds) throws ModelException {
        String dliTable = getTableNameFromPart(part);
        PdmTable pdmTable = schema.getTableByName(dliTable);
        String whereClause = "";
        if(PdmTable.PDM_TABLE_TYPE_FACT.equals(pdmTable.getType()) && snapshotIds != null && snapshotIds.length > 0) {
            String inClause = "";
            for(int i : snapshotIds) {
                if(inClause.length()>0)
                    inClause += ","+i;
                else
                    inClause = "" + i;
            }
            whereClause = ",SNAPSHOTS WHERE " + dliTable.toUpperCase() +
                    ".ID BETWEEN SNAPSHOTS.FIRSTID and SNAPSHOTS.LASTID AND SNAPSHOTS.ID IN (" + inClause + ")";
        }
        return whereClause;
    }

    protected String getLoadColumns(DLIPart part, PdmSchema schema) throws ModelException {
        String dliTable = getTableNameFromPart(part);
        PdmTable pdmTable = schema.getTableByName(dliTable);
        List<Column> columns = part.getColumns();
        String cols = "";
        for (Column cl : columns) {
            PdmColumn col = pdmTable.getColumnByName(cl.getName());
            // fact table fact columns
            if(PdmTable.PDM_TABLE_TYPE_FACT.equals(pdmTable.getType()) &&
                    SourceColumn.LDM_TYPE_FACT.equals(col.getLdmTypeReference()))
                decorateFactColumnForLoad(cols, cl, dliTable);
            // lookup table name column
            else if (PdmTable.PDM_TABLE_TYPE_LOOKUP.equals(pdmTable.getType()) &&
                    SourceColumn.LDM_TYPE_ATTRIBUTE.equals(col.getLdmTypeReference()))
                decorateLookupColumnForLoad(cols, cl, dliTable);
            else
                decorateOtherColumnForLoad(cols, cl, dliTable);
        }
        return cols;
    }

    protected String decorateFactColumnForLoad(String cols, Column cl, String table) {
        return decorateOtherColumnForLoad(cols, cl, table);
    }

    protected String decorateLookupColumnForLoad(String cols, Column cl, String table) {
        return decorateOtherColumnForLoad(cols, cl, table);
    }
    
    protected String decorateOtherColumnForLoad(String cols, Column cl, String table) {
        if (cols != null && cols.length() > 0)
            cols += "," + table.toUpperCase() + "." + StringUtil.formatShortName(cl.getName());
        else
            cols +=  table.toUpperCase() + "." + StringUtil.formatShortName(cl.getName());
        return cols;
    }

    protected String getTableNameFromPart(DLIPart part) {
        return StringUtil.formatShortName(part.getFileName().split("\\.")[0]);
    }

}