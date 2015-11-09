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
    
    private final SharedParameters sharedParameters;
    private boolean myChannelAnswered = false;
    private String uuid = "";
    
    public OutboundCallsHandler(SharedParameters sharedParameters) {
        this.sharedParameters = sharedParameters;
    }

    @Override
    protected void handleConnectResponse(ChannelHandlerContext ctx, 
            EslEvent event) {
        
        // Get UUID of monitored channel. It needs because special subscription
        // myevents and subscription to multicast events do not function in 
        // cooperate
        uuid = getUUID(event);
        
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
        specific subclass (replycenter::dropneedlesoutboundcalls), that used
        to inform rest of handlers about received answer.
        */
        switch (event.getEventName()) {
            
            case "CHANNEL_ANSWER":
                // It is event of our channel?
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    
                    log.info(
                            "Channel answered (call to {}).", 
                            event.getEventHeaders().
                                    get("Caller-Destination-Number")
                    );
                    
                    // If other handlers have not received the answer event
                    if (!sharedParameters.isAnswerDetected()) {
                        log.info("Drop all another calls.");
                        myChannelAnswered = true;
                        sendCustomEvent(
                                ctx.getChannel(), 
                                "DROP_ANOTHER_CALLS", 
                                "replycenter::dropneedlesoutboundcalls"
                        );
                    }
                }
                break;
                
        /*
        2) CUSTOM. If handler receives event of this type, then it concludes,
        that another channel CHANNEL_ANSWER event obtained. If so, handler 
        perform hangup application on its own channel.
        */
            case "CUSTOM":
                log.info("Received drop request. If received answer not {}", 
                        "in my channel, i will hangup my call.");
                if (!myChannelAnswered) {
                    log.info("It is not my answer. So i will drop my call now.");
                    hangup(ctx.getChannel());
                }
        /*
        3) CHANNEL_HANGUP. After we drop all needless calls state of current 
        session remain "answered". When "winner" channell hangup, we need to
        reset state. If myChannelAnswered is true, then this handler is that
        which received answer. Other handlers will ignore CHANNEL_HANGUP event.
        */
            case "CHANNEL_HANGUP":
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    if (myChannelAnswered) {
                        sharedParameters.setAnswerDetected(false);
                    }
                }
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
            log.info("Event {} was sent.", eventName);
        } else {
            log.error("Event {} was not sent.", eventName);
        }
    }
    
    /**
     * Perform FS event subscription: 
     * <br/> event plain CHANNEL_ANSWER CHANNEL_HANGUP
     * <br/> event plain CUSTOM replycenter::dropneedlesoutboundcalls
     * @param channel 
     * Current channel.
     */
    private void eventSubscription(Channel channel) {
        
        EslMessage response = sendSyncSingleLineCommand(channel, 
                "event plain CHANNEL_ANSWER CHANNEL_HANGUP");
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
            log.info("CHANNEL_ANSWER CHANNEL_HANGUP subscription successful" );
        } else {
            log.error("CHANNEL_ANSWER CHANNEL_HANGUP subscription failed: [{}]", 
                    response.getHeaderValue(EslHeaders.Name.REPLY_TEXT));
        }
        
        response = sendSyncSingleLineCommand(channel, 
                "event plain CUSTOM replycenter::dropneedlesoutboundcalls");
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT)
                .startsWith("+OK") ) {
            log.info("Subscription to event DROP_ANOTHER_CALLS successful" );
        } else {
            log.error("Subscription to event DROP_ANOTHER_CALLS failed: [{}]", 
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
            log.info("Call hangup successful");
        } else {
            log.error( "Call hangup failed: [{}}", 
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
        
        String dstExt = event.getEventHeaders().get("Caller-Destination-Number");
        
        log.info("Transfer {}...", dstExt);
        
        SendMsg transferMsg = new SendMsg();
        transferMsg.addCallCommand("execute");
        transferMsg.addExecuteAppName("transfer");
        transferMsg.addExecuteAppArg(dstExt + " XML drop_needless_oubound_calls");
        
        EslMessage response = 
                sendSyncMultiLineCommand(channel, transferMsg.getMsgLines());
        
        if (response.getHeaderValue(EslHeaders.Name.REPLY_TEXT).
                startsWith("+OK")) {
            log.info("Transfer successful");
        } else {
            log.error( "Transfer failed: [{}}", 
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
    
}
