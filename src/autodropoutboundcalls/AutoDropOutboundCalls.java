/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;


import org.freeswitch.esl.client.outbound.SocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author zh
 */
public class AutoDropOutboundCalls {
    
    private static final Logger logger = 
            LoggerFactory.getLogger("AutoDropOutboundCalls");
    private static boolean running = true;
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        
        SocketClient client = new SocketClient(48123, new SimplePipelineFactory());
        
        client.start();
        
        while (running) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                logger.info(ex.getMessage());
            }
        }
        
        client.stop();
    }
    
    public static void stop() {
        running = false;
    }
    
}
