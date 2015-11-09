/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;

import org.freeswitch.esl.client.outbound.AbstractOutboundClientHandler;
import org.freeswitch.esl.client.outbound.AbstractOutboundPipelineFactory;

/**
 *
 * @author zh
 */
public class SimplePipelineFactory extends AbstractOutboundPipelineFactory {
    
    private final SharedParameters sharedParameters = new SharedParameters();

    @Override
    protected AbstractOutboundClientHandler makeHandler() {
        return new OutboundCallsHandler(sharedParameters);
    }
    
}
