package org.sqlite.jdbc3;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Arrays;
import org.sqlite.ExtendedCommand;
import org.sqlite.ExtendedCommand.SQLExtension;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.CoreStatement;
import org.sqlite.core.DB;
import org.sqlite.core.DB.ProgressObserver;

public abstract class JDBC3Statement extends CoreStatement {
    // PUBLIC INTERFACE /////////////////////////////////////////////

    protected JDBC3Statement(SQLiteConnection conn) {
        super(conn);
    }

    /** @see java.sql.Statement#close() */
    public void close() throws SQLException {
        internalClose();
    }

    /** @see java.sql.Statement#execute(java.lang.String) */
    public boolean execute(String sql) throws SQLException {
        internalClose();

        SQLExtension ext = ExtendedCommand.parse(sql);
        if (ext != null) {
            ext.execute(conn.getDatabase());

            return false;
        }

        this.sql = sql;

        conn.getDatabase().prepare(this);
        return exec();
    }

    /**
     * @param closeStmt Whether to close this statement when the resultset is closed.
     * @see java.sql.Statement#executeQuery(java.lang.String)
     */
    public ResultSet executeQuery(String sql, boolean closeStmt) throws SQLException {
        rs.closeStmt = closeStmt;

        return executeQuery(sql);
    }

    /** @see java.sql.Statement#executeQuery(java.lang.String) */
    public ResultSet executeQuery(String sql) throws SQLException {
        internalClose();
        this.sql = sql;

        conn.getDatabase().prepare(this);

        if (!exec()) {
            internalClose();
            throw new SQLException("query does not return ResultSet", "SQLITE_DONE", SQLITE_DONE);
        }

        return getResultSet();
    }

    static class BackupObserver implements ProgressObserver {
        public void progress(int remaining, int pageCount) {
            System.out.println(String.format("remaining:%d, page count:%d", remaining, pageCount));
        }
    }

    /** @see java.sql.Statement#executeUpdate(java.lang.String) */
    public int executeUpdate(String sql) throws SQLException {
        return (int) executeLargeUpdate(sql);
    }

    /** @see java.sql.Statement#executeLargeUpdate(java.lang.String) */
    public long executeLargeUpdate(String sql) throws SQLException {
        internalClose();
        this.sql = sql;
        DB db = conn.getDatabase();

        long changes = 0;
        SQLExtension ext = ExtendedCommand.parse(sql);
        if (ext != null) {
            // execute extended command
            ext.execute(db);
        } else {
            try {
                changes = db.total_changes();

                // directly invokes the exec API to support multiple SQL statements
                int statusCode = db._exec(sql);
                if (statusCode != SQLITE_OK) throw DB.newSQLException(statusCode, "");

                changes = db.total_changes() - changes;
            } finally {
                internalClose();
            }
        }
        return changes;
    }

    /** @see java.sql.Statement#getResultSet() */
    public ResultSet getResultSet() throws SQLException {
        checkOpen();

        if (rs.isOpen()) {
            throw new SQLException("ResultSet already requested");
        }

        if (pointer.safeRunInt(DB::column_count) == 0) {
            return null;
        }

        if (rs.colsMeta == null) {
            rs.colsMeta = pointer.safeRun(DB::column_names);
        }

        rs.cols = rs.colsMeta;
        rs.emptyResultSet = !resultsWaiting;
        rs.open = true;
        resultsWaiting = false;

        return (ResultSet) rs;
    }

    /**
     * This function has a complex behaviour best understood by carefully reading the JavaDoc for
     * getMoreResults() and considering the test StatementTest.execute().
     *
     * @see java.sql.Statement#getUpdateCount()
     */
    public int getUpdateCount() throws SQLException {
        return (int) getLargeUpdateCount();
    }

    /**
     * This function has a complex behaviour best understood by carefully reading the JavaDoc for
     * getMoreResults() and considering the test StatementTest.execute().
     *
     * @see java.sql.Statement#getLargeUpdateCount()
     */
    public long getLargeUpdateCount() throws SQLException {
        DB db = conn.getDatabase();
        if (!pointer.isClosed()
                && !rs.isOpen()
                && !resultsWaiting
                && pointer.safeRunInt(DB::column_count) == 0) return db.changes();
        return -1;
    }

    /** @see java.sql.Statement#addBatch(java.lang.String) */
    public void addBatch(String sql) throws SQLException {
        internalClose();
        if (batch == null || batchPos + 1 >= batch.length) {
            Object[] nb = new Object[Math.max(10, batchPos * 2)];
            if (batch != null) System.arraycopy(batch, 0, nb, 0, batch.length);
            batch = nb;
        }
        batch[batchPos++] = sql;
    }

    /** @see java.sql.Statement#clearBatch() */
    public void clearBatch() throws SQLException {
        batchPos = 0;
        if (batch != null) for (int i = 0; i < batch.length; i++) batch[i] = null;
    }

    /** @see java.sql.Statement#executeBatch() */
    public int[] executeBatch() throws SQLException {
        return Arrays.stream(executeLargeBatch()).mapToInt(l -> (int) l).toArray();
    }

    /** @see java.sql.Statement#executeLargeBatch() */
    public long[] executeLargeBatch() throws SQLException {
        // TODO: optimize
        internalClose();
        if (batch == null || batchPos == 0) return new long[] {};

        long[] changes = new long[batchPos];
        DB db = conn.getDatabase();
        synchronized (db) {
            try {
                for (int i = 0; i < changes.length; i++) {
                    try {
                        this.sql = (String) batch[i];
                        db.prepare(this);
                        changes[i] = db.executeUpdate(this, null);
                    } catch (SQLException e) {
                        throw new BatchUpdateException(
                                "batch entry " + i + ": " + e.getMessage(), null, 0, changes, e);
                    } finally {
                        if (pointer != null) pointer.close();
                    }
                }
            } finally {
                clearBatch();
            }
        }

        return changes;
    }

    /** @see java.sql.Statement#setCursorName(java.lang.String) */
    public void setCursorName(String name) {}

    /** @see java.sql.Statement#getWarnings() */
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    /** @see java.sql.Statement#clearWarnings() */
    public void clearWarnings() throws SQLException {}

    /** @see java.sql.Statement#getConnection() */
    public Connection getConnection() throws SQLException {
        return conn;
    }

    /** @see java.sql.Statement#cancel() */
    public void cancel() throws SQLException {
        conn.getDatabase().interrupt();
    }

    /** @see java.sql.Statement#getQueryTimeout() */
    public int getQueryTimeout() throws SQLException {
        return conn.getBusyTimeout();
    }

    /** @see java.sql.Statement#setQueryTimeout(int) */
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) throw new SQLException("query timeout must be >= 0");
        conn.setBusyTimeout(1000 * seconds);
    }

    // TODO: write test
    /** @see java.sql.Statement#getMaxRows() */
    public int getMaxRows() throws SQLException {
        // checkOpen();
        return (int) rs.maxRows;
    }

    /** @see java.sql.Statement#getLargeMaxRows() */
    public long getLargeMaxRows() throws SQLException {
        // checkOpen();
        return rs.maxRows;
    }

    /** @see java.sql.Statement#setMaxRows(int) */
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    /** @see java.sql.Statement#setLargeMaxRows(long) */
    public void setLargeMaxRows(long max) throws SQLException {
        // checkOpen();
        if (max < 0) throw new SQLException("max row count must be >= 0");
        rs.maxRows = max;
    }

    /** @see java.sql.Statement#getMaxFieldSize() */
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    /** @see java.sql.Statement#setMaxFieldSize(int) */
    public void setMaxFieldSize(int max) throws SQLException {
        if (max < 0) throw new SQLException("max field size " + max + " cannot be negative");
    }

    /** @see java.sql.Statement#getFetchSize() */
    public int getFetchSize() throws SQLException {
        return ((ResultSet) rs).getFetchSize();
    }

    /** @see java.sql.Statement#setFetchSize(int) */
    public void setFetchSize(int r) throws SQLException {
        ((ResultSet) rs).setFetchSize(r);
    }

    /** @see java.sql.Statement#getFetchDirection() */
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }

    /** @see java.sql.Statement#setFetchDirection(int) */
    public void setFetchDirection(int direction) throws SQLException {
        switch (direction) {
            case ResultSet.FETCH_FORWARD:
            case ResultSet.FETCH_REVERSE:
            case ResultSet.FETCH_UNKNOWN:
                // No-op: SQLite does not support a value other than FETCH_FORWARD
                break;
            default:
                throw new SQLException(
                        "Unknown fetch direction "
                                + direction
                                + ". "
                                + "Must be one of FETCH_FORWARD, FETCH_REVERSE, or FETCH_UNKNOWN in java.sql.ResultSet");
        }
    }

    /**
     * As SQLite's last_insert_rowid() function is DB-specific not statement specific, this function
     * introduces a race condition if the same connection is used by two threads and both insert.
     *
     * @see java.sql.Statement#getGeneratedKeys()
     */
    public ResultSet getGeneratedKeys() throws SQLException {
        return conn.getSQLiteDatabaseMetaData().getGeneratedKeys();
    }

    /**
     * SQLite does not support multiple results from execute().
     *
     * @see java.sql.Statement#getMoreResults()
     */
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(0);
    }

    /** @see java.sql.Statement#getMoreResults(int) */
    public boolean getMoreResults(int c) throws SQLException {
        checkOpen();
        internalClose(); // as we never have another result, clean up pointer
        return false;
    }

    /** @see java.sql.Statement#getResultSetConcurrency() */
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }

    /** @see java.sql.Statement#getResultSetHoldability() */
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    /** @see java.sql.Statement#getResultSetType() */
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    /** @see java.sql.Statement#setEscapeProcessing(boolean) */
    public void setEscapeProcessing(boolean enable) throws SQLException {
        if (enable) {
            throw unused();
        }
    }

    protected SQLException unused() {
        return new SQLException("not implemented by SQLite JDBC driver");
    }

    // Statement ////////////////////////////////////////////////////

    public boolean execute(String sql, int[] colinds) throws SQLException {
        throw unused();
    }

    public boolean execute(String sql, String[] colnames) throws SQLException {
        throw unused();
    }

    public int executeUpdate(String sql, int autoKeys) throws SQLException {
        throw unused();
    }

    public int executeUpdate(String sql, int[] colinds) throws SQLException {
        throw unused();
    }

    public int executeUpdate(String sql, String[] cols) throws SQLException {
        throw unused();
    }

    public boolean execute(String sql, int autokeys) throws SQLException {
        throw unused();
    }
}
