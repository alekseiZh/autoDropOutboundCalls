/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;

import org.freeswitch.esl.client.outbound.AbstractOutboundClientHandler;
import org.freeswitch.esl.client.transport.SendMsg;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslHeaders;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

/**
 *
 * @author zh
 */
public class OutboundCallsHandler extends AbstractOutboundClientHandler {
    
    private String uuid;
    private final DBInterface dbi = new DBInterface(
            "jdbc:jtds:sqlserver://127.0.0.1:1433;"
                    + "databaseName=Callcenter;"
                    + "user=callcenter;"
                    + "password=call"
    );
    private int groupID;
    private String destination;
    private String taskName;
    
    /**
     *
     * @param ctx
     * @param event
     */
    @Override
    protected void handleConnectResponse(ChannelHandlerContext ctx, 
            EslEvent event) {
        
        // Get destination
        destination = event.getEventHeaders().get("Caller-Destination-Number");
        
        // Get UUID of monitored channel. It needs because special subscription
        // myevents and subscription to multicast events do not function in 
        // cooperate. Then we will use multicast CHANNEL_ANSWER and 
        // CHANNEL_HANGUP rather than myevents. UUID is identifier that help as
        // to distinguish events of our channel from events of another channels.
        uuid = getUUID(event);
        
        // Get operators group ID
        groupID = getGroupID(event);
        
        // Get task unique name
        taskName = getTaskName(event);
        
        // Register handler for idle operator count monitoring
        OperMonitor.registerCallsHandler(this);
        
        // Perform event subcriptions
        eventSubscription(ctx.getChannel());
        
        // Perform transfer action of the channel, because we wont to manipulate
        // of further call routing through traditional dialplan.
         transfer(ctx.getChannel(), event);
    }

    @Override
    protected void handleEslEvent(ChannelHandlerContext ctx, EslEvent event) {
        /*
        We need to handle 3 types of events:
        1) CHANNEL_ANSWER. When handler receives this event, it need to check 
        whether it relates to its channel. If it is, a handler unregiters from
        operator monitor, and this call never be hanged up by this service.
        */
        switch (event.getEventName()) {
            
            case "CHANNEL_ANSWER":
                // It is event of our channel?
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    
                    log.info("Dst: {} : Channel answered.", destination);
                    
                    // Unregister handler for operators count monitoring
                    OperMonitor.unregisterCallsHandler(this);
                    
                    // No need to events from FS now
                    blockHandler(ctx.getChannel());
                }
                break;
        /*
        2) CHANNEL_HANGUP. If call hanged up before answer or before service do
        that hangup, then we need to manually unregister this handler.
        */
            case "CHANNEL_HANGUP":
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    // Unregister handler for operators count monitoring
                    OperMonitor.unregisterCallsHandler(this);
                }
                break;
                
        /*
        2) CUSTOM. If handler receives event of this type, then it concludes,
        that another channel CHANNEL_ANSWER event obtained and there is no idle 
        operators. If so, handler perform hangup application on its own channel
        and unregisters from operators monitor.
        */
            case "CUSTOM":
                log.info("Dst: {} : Received drop request. Hangup call now.", 
                        destination);
                hangup(ctx.getChannel());
                OperMonitor.unregisterCallsHandler(this);
                break;
        }
    }
    
    /**
     * Perform FS event subscription: 
     * <br/> event plain CHANNEL_ANSWER CHANNEL_HANGUP
     * <br/> event plain CUSTOM replycenter::dropneedlessoutboundcalls
     * @param channel 
     * Current channel.
     */
    private void eventSubscription(Channel channel) {
        
        EslMessage response = sendSyncSingleLineCommand(channel, 
                "event plain CHANNEL_ANSWER CHANNEL_HANGUP");
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
            log.info("Dst: {} : CHANNEL_ANSWER CHANNEL_HANGUP subscription successful", destination );
        } else {
            log.error("Dst: {} : CHANNEL_ANSWER CHANNEL_HANGUP subscription failed: [{}]", 
                    destination,
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
        
        response = sendSyncSingleLineCommand(channel, 
                "event plain CUSTOM replycenter::dropneedlessoutboundcalls");
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
            log.info("Dst: {} : Subscription to event DROP_ANOTHER_CALLS successful", destination );
        } else {
            log.error("Dst: {} : Subscription to event DROP_ANOTHER_CALLS failed: [{}]", 
                    destination,
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
    }
    
    /**
     * Perform hangup action on channel.
     * @param channel
     * Current channel.
     */
    public void hangup(Channel channel) {
        
        SendMsg hangupMsg = new SendMsg();
        hangupMsg.addCallCommand("execute");
        hangupMsg.addExecuteAppName("hangup");
        
        EslMessage response = 
                sendSyncMultiLineCommand(channel, hangupMsg.getMsgLines());
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT).
                startsWith("+OK")) {
            log.info("Dst: {} : Call hangup successful", destination);
        } else {
            log.error( "Dst: {} : Call hangup failed: [{}}", 
                    destination,
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
    }
    
    /**
     * Execute transfer application on channel. Transfer destionation -- 
     * specific dialplan instance which name is drop_needless_oubound_calls.
     * In fact application, that will be executed looks like:
     * <br/> transfer dstNumber XML drop_needless_oubound_calls.
     * <br/> where dstNumber is number, that we get from event.
     * @param channel
     * Current channel.
     * @param event 
     * Initial event in response from Freeswitch. Needs to get destination 
     * number.
     */
    private void transfer(Channel channel, EslEvent event) {
        
        log.info("Dst: {} : Transfer ...", destination);
        
        SendMsg transferMsg = new SendMsg();
        transferMsg.addCallCommand("execute");
        transferMsg.addExecuteAppName("transfer");
        transferMsg.addExecuteAppArg(destination + 
                " XML drop_needless_oubound_calls");
        
        EslMessage response = 
                sendSyncMultiLineCommand(channel, transferMsg.getMsgLines());
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT).
                startsWith("+OK")) {
            log.info("Dst: {} : Transfer successful", destination);
        } else {
            log.error( "Dst: {} : Transfer failed: [{}}", 
                    destination,
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
        
    }
    
    /**
     * Get UUID from event.
     * @param event
     * Event from FreeSWITCH.
     * @return 
     * UUID of channel.
     */
    private String getUUID(EslEvent event) {
        return event.getEventHeaders().get("Unique-ID");
    }
    
    /**
     * Return value of channel variable outbound_task_group, which is represent 
     * id of group of operators, that serves current outbound task,
     * @param event
     * Event from which variable will be received.
     * @return 
     * Value of variable outbound_task_group.
     */
    private int getGroupID(EslEvent event) {
        String id = event.getEventHeaders().get("variable_outbound_task_group");
        log.info("Dst: {} : GroupID: {}", destination, id);
        if (id == null) {
            return 1;
        }
        return Integer.valueOf(id);
    }
    
    /**
     * Return value of channel variable outbound_task_name, which is represent 
     * unique name of outbound task.
     * @param event
     * Event from which variable will be received.
     * @return 
     * Value of variable outbound_task_name.
     */
    private String getTaskName(EslEvent event) {
        String name = event.getEventHeaders().get("variable_outbound_task_name");
        log.info("Dst: {} : Task: {}", destination, name);
        if (name == null) {
            return "null";
        }
        return name;
    }
    
    /**
     * Disable event subscription for this hanlder.
     * @param channel 
     */
    private void blockHandler(Channel channel) {
        
        EslMessage response = sendSyncSingleLineCommand(channel, 
                "noevents");
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
            log.info("Dst: {} : events subscription unregister successeful.", 
                    destination );
        } else {
            log.error("Dst: {} : events subscription unregister successeful failed: [{}]", 
                    destination,
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
    }

    public int getGroupID() {
        return groupID;
    }

    public String getTaskName() {
        return taskName;
    }
    
    
}
