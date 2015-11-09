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
    protected void handleConnectResponse(ChannelHandlerContext ctx, EslEvent event) {
        uuid = getUUID(event);
        eventSubscription(ctx.getChannel());
        transfer(ctx.getChannel(), event);
    }

    @Override
    protected void handleEslEvent(ChannelHandlerContext ctx, EslEvent event) {
        
        switch (event.getEventName()) {
            
            case "CHANNEL_ANSWER":
                if (event.getEventHeaders().get("Unique-ID").equals(uuid)) {
                    
                    log.info(
                            "Channel answered (call to {}).", 
                            event.getEventHeaders().
                                    get("Caller-Destination-Number")
                    );

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
                
            case "CUSTOM":
                log.info("Received drop request. If received answer not {}", 
                        "in my channel, i will hangup my call.");
                if (!myChannelAnswered) {
                    log.info("It is not my answer. So i will drop my call now.");
                    hangup(ctx.getChannel());
                }
            
            case "CHANNEL_HANGUP":
                if (myChannelAnswered) {
                    sharedParameters.setAnswerDetected(false);
                }
        }
    }
    
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

    private String getUUID(EslEvent event) {
        return event.getEventHeaders().get("Unique-ID");
    }
    
}
