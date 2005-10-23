/*
 * org.osjava.jdbc.sqlite.Statement
 * $Id$
 * $Rev$ 
 * $Date$ 
 * $Author$
 * $URL$
 * 
 * Created on Jul 2, 2005
 *
 * Copyright (c) 2004, Robert M. Zigweid All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * + Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer. 
 *
 * + Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution. 
 *
 * + Neither the name of the SQLite-JDBC nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without 
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.osjava.jdbc.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * @author rzigweid
 * 
 */
public class Statement implements java.sql.Statement {
    /**
     * The connection that the Statement is associated with.
     */
    private Connection con;
    
    /**
     * LinkedList of ResultSets created by the statement.
     */
    private Collection results = new LinkedList();

    /**
     * The fetch direction from the statement. The default is
     * ResultSet.FETCH_FORWARD.
     */
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    
    /**
     * The ResultSet type that will be the default for the ResultSets created
     * from this statement.  The default is ResultSet.TYPE_FORWARD_ONLY;
     */
    private int resultSetType;

    private int resultSetConcurrency;

    private int resultSetHoldability;
    
    private int fetchSize = 1024;
    
    /**
     * The maximum size of a ResultSet in rows.  0 means there is no maximum.
     */
    private int maxRows = 0;

    /**
     * Create a new Statement object. ResultSets that are created from this
     * statement will have a default fetch direction of
     * {@link java.sql.ResultSet#FETCH_FORWARD}.
     * 
     * @param con the Connection object that this Statement is associated with.
     * @param resultSetType sets the initial result set type for ResultSets that
     *        are generated from this Statement.
     * @param resultSetConcurrency sets the initial result set concurrency for
     *        ResultSets that are generated from this Statement.
     * @param resultSetHoldability the initial result set holdability for
     *        ResultSets that are generated from this Statement.
     * @throws SQLException if any of the parameter values are out of range.
     */
    /* Ugh, package private. */
    Statement(Connection con, int resultSetType, int resultSetConcurrency, int resultSetHoldability) 
        throws SQLException {
        if (resultSetType != java.sql.ResultSet.TYPE_FORWARD_ONLY
            && resultSetType != java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE
            && resultSetType != java.sql.ResultSet.TYPE_SCROLL_SENSITIVE) {
            throw new SQLException("Cannot create Statement.  Invalid resultSetType.");
        }
        if (   resultSetConcurrency != java.sql.ResultSet.CONCUR_READ_ONLY
            && resultSetConcurrency != java.sql.ResultSet.CONCUR_UPDATABLE) {
            throw new SQLException("Cannot create Statement.  Invalid resultSetConcurrency.");
        }
        if (   resultSetHoldability != java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT
            && resultSetHoldability != java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw new SQLException("Cannot create Statement.  Invalid resultSetHoldability.");
        }
        this.con = con;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeQuery(java.lang.String)
     */
    public java.sql.ResultSet executeQuery(String sql) throws SQLException {
        /* If the Connection is in autocommit mode, always commit before
         * a new statement is executed. */
        if(con.getAutoCommit()) {
            ((org.osjava.jdbc.sqlite.Connection)con).commit(true);
        }
        /* Create a new java.sql.ResultSet object that will be filled. */
        ResultSet rs = new ResultSet(this, resultSetType, resultSetConcurrency, resultSetHoldability, this);
        results.add(rs);
        executeSQLWithResultSet(sql, con, rs, getFetchSize());
        return rs;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeUpdate(java.lang.String)
     */
    public int executeUpdate(String sql) throws SQLException {
        /* If the Connection is in autocommit mode, always commit before
         * a new statement is executed. */
        if(con.getAutoCommit()) {
            /* Do not allow BEGIN, COMMIT, or ROLLBACK statements in
             * autoCommit mode. */
            try {
                executeSQL(sql, con);
            } catch (SQLException e) {
                /* Squash the exception raised about starting a transaction from
                 * within another transaction if and only if the statement starts
                 * with BEGIN and we are in auto-commit mode */
                /* XXX: There is probably a better way to do this */
                if(    e.getMessage().equals("cannot start a transaction within a transaction")
                    && sql.startsWith("BEGIN")
                    && con.getAutoCommit()) {
                    System.err.println("Found a begin in the middle of a transaction.");
                    return 0;
                }
                e.printStackTrace();
            }
            /* Even in autocommit if the statement begins with 'BEGIN' 
             * or 'COMMIT' don't try to commit it afterwards */
            if(!sql.startsWith("COMMIT") && !sql.startsWith("BEGIN")) {
                ((org.osjava.jdbc.sqlite.Connection)con).commit(true);
            }
            System.err.println("Found a commit or begin statement while in autocommit mode.");
            return 0;
        }
        int count = -1;
        try {
            count = executeSQL(sql, con);
        } catch (SQLException e) {
            System.err.println("Error executing statement -- " + e.getMessage());
            /* Squash the exception raised about starting a transaction from
             * within another transaction if and only if the statement starts
             * with BEGIN and we are in auto-commit mode */
            /* XXX: There is probably a better way to do this */
            if(    e.getMessage().equals("cannot start a transaction within a transaction")
                && sql.startsWith("BEGIN")
                && con.getAutoCommit()) {
                System.err.println("Found a begin in the middle of a transaction.");
                return 0;
            }
            e.printStackTrace();
        }
        System.err.println("DONE!");
        /* FIXME: Should look for -1 as the value of count and probably return
         *        an error (throw exception) */        
        return count;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeUpdate(java.lang.String, int)
     */
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        /* If the Connection is in autocommit mode, always commit before
         * a new statement is executed. */
        if(con.getAutoCommit()) {
            ((org.osjava.jdbc.sqlite.Connection)con).commit(true);
        }
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeUpdate(java.lang.String, int[])
     */
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        /* If the Connection is in autocommit mode, always commit before
         * a new statement is executed. */
        if(con.getAutoCommit()) {
            ((org.osjava.jdbc.sqlite.Connection)con).commit(true);
        }
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeUpdate(java.lang.String,
     *      java.lang.String[])
     */
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        /* If the Connection is in autocommit mode, always commit before
         * a new statement is executed. */
        if(con.getAutoCommit()) {
            ((org.osjava.jdbc.sqlite.Connection)con).commit(true);
        }
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#close()
     */
    public void close() throws SQLException {
        /* Ensure that all of the Statement's ResultSets are closed. */
        Iterator it= results.iterator();
        while (it.hasNext()) {
            ResultSet next = (ResultSet)it.next();
            it.remove();
            next.close();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getMaxFieldSize()
     */
    public int getMaxFieldSize() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setMaxFieldSize(int)
     */
    public void setMaxFieldSize(int max) throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getMaxRows()
     */
    public int getMaxRows() throws SQLException {
        return maxRows;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setMaxRows(int)
     */
    public void setMaxRows(int max) throws SQLException {
        if(max < 0) {
            throw new SQLException("Invalid parameter for ResultSet.setMaxRows().  Integer must be >= 0.");
        }
        maxRows = max;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setEscapeProcessing(boolean)
     */
    public void setEscapeProcessing(boolean enable) throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getQueryTimeout()
     */
    public int getQueryTimeout() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setQueryTimeout(int)
     */
    public void setQueryTimeout(int seconds) throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#cancel()
     */
    public void cancel() throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getWarnings()
     */
    public SQLWarning getWarnings() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#clearWarnings()
     */
    public void clearWarnings() throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setCursorName(java.lang.String)
     */
    public void setCursorName(String name) throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#execute(java.lang.String, java.lang.String[])
     */
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#execute(java.lang.String, int[])
     */
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#execute(java.lang.String, int)
     */
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#execute(java.lang.String)
     */
    public boolean execute(String sql) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getjava.sql.ResultSet()
     */
    public java.sql.ResultSet getResultSet() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getUpdateCount()
     */
    public int getUpdateCount() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getMoreResults()
     */
    public boolean getMoreResults() throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setFetchDirection(int)
     */
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != java.sql.ResultSet.FETCH_FORWARD || direction != java.sql.ResultSet.FETCH_REVERSE
            || direction != java.sql.ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("Invalid fetch direction");
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getFetchDirection()
     */
    public int getFetchDirection() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#setFetchSize(int)
     */
    public void setFetchSize(int rows) throws SQLException {
        int max = getMaxRows();
        if(max == 0) {
            if(rows < 0) {
                throw new SQLException("Invalid fetch size.  Value must be greater than 0.");
            }
        } else if(rows < 0 || rows > getMaxRows()) {
            throw new SQLException("Invalid fetch size.  Value must be between 0 and " + getMaxRows() +".");
        }
        fetchSize = rows;
    }

    /**
     * @see java.sql.Statement#getFetchSize()
     */
    public int getFetchSize() throws SQLException {
        return fetchSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getjava.sql.ResultSetConcurrency()
     */
    public int getResultSetConcurrency() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getjava.sql.ResultSetType()
     */
    public int getResultSetType() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#addBatch(java.lang.String)
     */
    public void addBatch(String sql) throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#clearBatch()
     */
    public void clearBatch() throws SQLException {
    // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#executeBatch()
     */
    public int[] executeBatch() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getConnection()
     */
    public Connection getConnection() throws SQLException {
        return con;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getMoreResults(int)
     */
    public boolean getMoreResults(int current) throws SQLException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getGeneratedKeys()
     */
    public java.sql.ResultSet getGeneratedKeys() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.sql.Statement#getjava.sql.ResultSetHoldability()
     */
    public int getResultSetHoldability() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }
    
    /* Helper methods */
    /**
     * Remove a ResultSet from the list of tracked results
     */
    void removeResult(ResultSet rs) {
        results.remove(rs);
    }
    
    /**
     * Begin a new transaction.  This method can only be used in autocommit
     * mode and is designed only to be used by the Connection object.
     * @throws SQLException
     */
    void forceBegin() throws SQLException {
        System.err.println("ForceBegin");
        if(!con.getAutoCommit()) {
            throw new SQLException("Cannot forceBegin when not in autocommit mode.");
        }
        try {
            executeSQL("BEGIN;", con);
        } catch(SQLException e) {
            System.err.println("failed to begin transaction");
        }
    }
    
    /**
     * Commit a transaction without going through autocommit checks.
     * 
     * This is necessary at the moment to avoid looping checks in auto-
     * commit for begin and end statements.  If the Connection is in 
     * auto-commit mode, then a BEGIN statement is also issued to start
     * the next transaction.
     * 
     * @throws SQLException if an exception occurs.  
     */ 
    void forceCommit() throws SQLException {
        System.err.println("Force Commit");
        executeSQL("COMMIT;", con);
        /* If in auto-commit mode, always start a new transaction immediately */
        if(con.getAutoCommit())  {
            executeSQL("BEGIN;", con);
        }
    }

    /* Native components */
    private native int executeSQL(String sql, Connection con) throws SQLException;
    
    private native void executeSQLWithResultSet(String sql,
                                                Connection con,
                                                ResultSet rs,
                                                int numRows)
            throws SQLException;    
}
