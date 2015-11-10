/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;

import java.util.ArrayList;
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
class OutboundCallsHandler extends AbstractOutboundClientHandler {
    
    private SharedParameters sharedParameters;
    private String uuid = "";
    private final DBInterface dbi = new DBInterface(
            "jdbc:jtds:sqlserver://127.0.0.1:1433;"
                    + "databaseName=Callcenter;"
                    + "user=callcenter;"
                    + "password=call"
    );
    private int groupID;
    private String destination;
    private String taskName;
    
    public OutboundCallsHandler(SharedParameters sharedParameters) {
        this.sharedParameters = sharedParameters;
    }

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
        
        // Get intance of SharedParameters. It need to process few of outbound
        // calls tasks. outbound_call_task_id - special channel variable, that
        // idetify certain task. If variable outbound_call_task_id not found
        // then 
        createSharedParameters(event, "variable_outbound_call_task_id");
        
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
        whether it relates to its channel. If it is, then handler check whether
        the answer on other channel before. If not, he fires CUSTOM event with
        specific subclass (replycenter::dropneedlessoutboundcalls), that used
        to inform rest of handlers about received answer.
        */
        switch (event.getEventName()) {
            
            case "CHANNEL_ANSWER":
                // It is event of our channel?
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    
                    log.info("Dst: {} : Channel answered.", destination);
                    
                    synchronized(sharedParameters) {
                        int n = dbi.numberOfIdleOpers(groupID);
                        
                        if (n > 0) {
                            blockHandler(ctx.getChannel());
                        } else {
                            blockHandler(ctx.getChannel());
                            
                            log.info("Dst: {} : Drop all another calls.", 
                                    destination);
                            sendCustomEvent(
                                ctx.getChannel(), 
                                "DROP_ANOTHER_CALLS", 
                                "replycenter::dropneedlessoutboundcalls"
                            );
                        }
                    }
                }
                break;
                
        /*
        2) CUSTOM. If handler receives event of this type, then it concludes,
        that another channel CHANNEL_ANSWER event obtained. If so, handler 
        perform hangup application on its own channel.
        */
            case "CUSTOM":
                log.info("Dst: {} : Received drop request. Hangup call now.", 
                        destination);
                hangup(ctx.getChannel());
                break;
        }
    }
    
    /**
     * Send to FS event of type CUSTOM with custom subclass.
     * 
     * @param channel
     * Current channel
     * @param eventName
     * Event name. May be any that you like.
     * @param subClass 
     * Subclass of event. See documentation of Freeswitch to details.
     */
    private void sendCustomEvent(Channel channel, String eventName, 
            String subClass) {
        
        ArrayList<String> msgLines = new ArrayList<>();
        msgLines.add("sendevent " + eventName);
        msgLines.add("Event-Subclass: " + subClass);
        msgLines.add("Event-Name: CUSTOM");
        
        EslMessage response = sendSyncMultiLineCommand(channel, msgLines);

        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK")) {
            log.info("Dst: {} : Event {} was sent.", destination, eventName);
        } else {
            log.error("Dst: {} : Event {} was not sent.", destination, eventName);
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
    private void hangup(Channel channel) {
        
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
     * Return outbound call task id from custom channel variable.
     * @param event
     * @param variableName
     * @return
     * TaskId string or "default" if variable not found in event.
     */
    private void createSharedParameters(EslEvent event, String variableName) {
        
        String taskId = event.getEventHeaders().get(variableName);
        
        if (taskId == null) {
            taskId = "default";
        }
        
        log.info("Dst: {} : TaskID: {}", destination, taskId);
        
        sharedParameters = SharedParameters.getSharedParametersInstance(taskId);
        
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
}
