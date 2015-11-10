/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author zh
 */
public class DBInterface {
    
    private final String connString;
    private Connection conn;
    
    private final int maxConnectAtemmpts = 2;
    private final int maxConnectTimeout = 10000;
    private boolean reconnectFlag;
    private final boolean endlessReconnect = true;
    private final Logger logger = LoggerFactory.getLogger(DBInterface.class);
    
    /**
     * 
     * @param connString 
     * jdbc connection string.
     * Example:
     * jdbc:jtds:sqlserver://127.0.0.1:1433;databaseName=Callcenter;user=callcenter;password=call;
     */
    public DBInterface(String connString) {
        this.connString = connString;
    }
    
    /**
     * Connect to DB. If connection attempt is failed, reconnect is perform.
     * 
     * @param connString
     * jdbc connection string.
     * Example:
     * jdbc:jtds:sqlserver://127.0.0.1:1433;databaseName=Callcenter;user=callcenter;user=callcenter;
     */
    private void connectToDB(String connString) {
        
        logger.debug("Start method: connectToDB");
        
        int connectAttempt = 0;
        int connectTimeout = 500;
        reconnectFlag = true;
        
        while (reconnectFlag) {
            
            if (++connectAttempt > maxConnectAtemmpts && !endlessReconnect) {
                logger.info("Max number of reconnect attempt distinguished.");
                throw new ConnectionFailException("We cannot connect to freeswitch...");
            }
            
            logger.debug("Connect to DB,attempt {}, connection string: {}", 
                connectAttempt, connString);
            
            try {
                conn = DriverManager.getConnection(connString);
                reconnectFlag = false;
            
            } catch (SQLException e) {
                if (e.getErrorCode() == 0) {

                    logger.debug("Connection to DB failed");

                    if (connectTimeout < maxConnectTimeout) {
                        connectTimeout = connectTimeout * 2;
                        if (connectTimeout > maxConnectTimeout) {
                            connectTimeout = maxConnectTimeout;
                        }
                    }

                    logger.debug("pause: {} ms", connectTimeout);

                    try {
                        Thread.sleep(connectTimeout);
                    } catch (InterruptedException ex) {
                        logger.debug(ex.getMessage());
                    }

                } else {
                    logger.debug("Connection failed. Stopping daemon.");
                    throw new ConnectionFailException();
                }
            }
        }
    }
    
    /**
     * Connect to DB and execute stored procedure reply_online_oper_for_group,
     * which return operators ids with "online" status for specific group. 
     * Return number of operators, which have online status.
     * 
     * @param grID
     * id of group which operators status checking perform.
     * 
     * @return
     * Number of operators that have idle status.
     */
    public int numberOfIdleOpers(int grID) {
        
        logger.debug("Start method numberOfIdleOpers");
        
        connectToDB(connString);
        
        int idleOpers = 0;
        
        try {
            
            String query = "EXEC [reply_online_oper_for_group] @groupid = " 
                    + grID;
            logger.info("Is there idle opers?");
            
            ResultSet resultSet = conn.createStatement().executeQuery(query);
            
            while (resultSet.next()) {                
                idleOpers++;
            }
            
        } catch (Exception e) {
            logger.error(e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                logger.error(e.getMessage());
            }
        }
        
        logger.info("Number of idle opers: {}", idleOpers);
        return idleOpers;
    }
}