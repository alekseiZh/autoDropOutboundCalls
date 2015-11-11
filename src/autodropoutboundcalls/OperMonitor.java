package autodropoutboundcalls;


import java.util.ArrayList;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.inbound.InboundConnectionFailure;
import org.freeswitch.esl.client.transport.message.EslHeaders;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author zh
 */
public class OperMonitor extends Thread {
    
    private final ArrayList<OutboundCallsHandler> handlers = new ArrayList<>();
    private String taskID = "default";
    private int groupID = 1;
    private boolean running = true;
    
    private final static Logger staticLogger 
            = LoggerFactory.getLogger("autodropoutboundcalls.OperMonitor_static");
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final DBInterface dbi = new DBInterface(
            "jdbc:jtds:sqlserver://127.0.0.1:1433;"
                    + "databaseName=Callcenter;"
                    + "user=callcenter;"
                    + "password=call"
    );
    
    private static final ArrayList<OperMonitor> monitors 
            = new ArrayList<>();
    
    private OperMonitor(String taskID, int groupID) {
        this.taskID = taskID;
        this.groupID = groupID;
    }
    
    /**
     * Return OperMmonitor instance.
     * @param handler
     */
    public synchronized 
            static void registerCallsHandler(OutboundCallsHandler handler) {
                
        staticLogger.debug("Handler: Task: {}, Group: {}: start registration.", handler.getTaskName(), handler.getGroupID());
                
        for (OperMonitor monitor : monitors) {
            
            // Find monitor with this task id
            if (monitor.taskID.equals(handler.getTaskName())) {
                
                staticLogger.debug("Handler: Task: {}, Group: {}: monitor found.", handler.getTaskName(), handler.getGroupID());
                
                // Handler already registered?
                for (OutboundCallsHandler h : monitor.handlers) {
                    staticLogger.debug("Handler: Task: {}, Group: {}: already registered.", handler.getTaskName(), handler.getGroupID());
                    if (h == handler) {
                        return;
                    }
                }
                
                // Add new handler to list of handlers
                monitor.handlers.add(handler);
                
                staticLogger.debug("Handler: Task: {}, Group: {}: registration OK.", handler.getTaskName(), handler.getGroupID());
                
                // If monitor not started
                staticLogger.debug("Handler: Task: {}, Group: {}: restart monitoring.", handler.getTaskName(), handler.getGroupID());
                synchronized (monitor) { monitor.notify(); }
                
                return;
            }
        }
        
        // Required monitor not found. Add new.
        staticLogger.debug("Handler: Task: {}, Group: {}: monitor not found. Create new one.", handler.getTaskName(), handler.getGroupID());
        OperMonitor monitor = new OperMonitor(handler.getTaskName(), 
                handler.getGroupID());
        
        // Add current handler
        monitor.handlers.add(handler);
        
        // Add monitor to list of monitord
        monitors.add(monitor);
        staticLogger.debug("Handler: Task: {}, Group: {}: registration OK.", handler.getTaskName(), handler.getGroupID());
        
        // Start monitoring
        staticLogger.debug("Handler: Task: {}, Group: {}: start monitoring.", handler.getTaskName(), handler.getGroupID());
        monitor.start();
    }
    
    /**
     * Perform unregistering of {@link OutboundCallsHandler} instance. If this
     * instance not registered, nothing happen.
     * 
     * @param handler 
     * Unregistering handler.
     */
    public synchronized 
            static void unregisterCallsHandler(OutboundCallsHandler handler) {
                
        for (OperMonitor monitor : monitors) {
            if (monitor.taskID.equals(handler.getTaskName())) {
                staticLogger.debug("Handler: Task: {}, Group: {}: unregistration OK.", 
                        handler.getTaskName(), handler.getGroupID());
                monitor.handlers.remove(handler);
            }
        }
    }

    @Override
    public void run() {
        while (running) {
            if (handlers.isEmpty()) {
                waitForNewHandlers(1*60*1000);
            } else {
                monitorOperators();
            }
        }
    }
    
    
    /**
     * 
     */
    private synchronized void waitForNewHandlers(long timeout) {
        
        logger.debug("Task: {}, Group: {}. Waiting for new handlers: ...", 
                taskID, groupID);
        
        long beginTime = System.currentTimeMillis();
        
        try {
            wait(timeout);
            if (timeout <= System.currentTimeMillis() - beginTime ) {
                logger.debug("Task: {}, Group: {}. Waiting for new handlers: timeout.", 
                taskID, groupID);
                stopMonitoring();
            } else {
                logger.debug("Task: {}, Group: {}. Waiting for new handlers: return to work.", 
                taskID, groupID);
            }
        } catch (InterruptedException ex) {
            logger.debug(ex.getMessage());
        }
    }
    
    /**
     * Stop monitoring and remove this monitor from monitor list
     */
    private void stopMonitoring() {
        logger.debug("Task: {}, Group: {}. Stop monitoring.", taskID, groupID);
        monitors.remove(this);
        running = false;
    }
    
    /**
     * Monitor count of idle operators. If count equals 0, then perform sending 
     * an CUSTOM event with subclass replycenter::dropneedlessoutboundcalls.
     */
    private void monitorOperators() {
        if (dbi.numberOfIdleOpers(groupID) == 0) {
            sendHangupEvent(
                    "HANGUP_OTHER_CALLS", 
                    "replycenter::dropneedlessoutboundcalls"
            );
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            logger.debug(ex.getMessage());
        }
    }
    
    /**
     * Send to FS event of type CUSTOM with custom subclass.
     * 
     * @param eventName
     * Event name. May be any what you like.
     * @param subClass 
     * Subclass of event. See documentation of Freeswitch for details.
     */
    private void sendHangupEvent(String eventName, String subClass) {
        
        Client fsClient = new Client();
        
        try {
            fsClient.connect("127.0.0.1", 8021, "ClueCon", 5);
            
            ArrayList<String> commandLines = new ArrayList<>();
            commandLines.add("sendevent " + eventName);
            commandLines.add("Event-Name: CUSTOM");
            commandLines.add("Event-Subclass: " + subClass);
            
            EslMessage response = 
                    fsClient.sendSyncMultiLineCommand(commandLines);
            
            if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
                logger.debug("Sending event {} {}: OK", eventName, subClass );
            } else {
                logger.error("Sending event {} {}: FAIL", eventName, subClass );
            }

        } catch (InboundConnectionFailure ex) {
            logger.error(ex.getMessage());
        }
    }
}
