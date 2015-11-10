/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;

import java.util.HashMap;


/**
 *
 * @author zh
 */
class SharedParameters {
    
    private boolean answerDetected = false;
    private static HashMap<String, SharedParameters> instances 
            = new HashMap<>();
    
    /**
     * If annswer has been detected, then return true.
     * @return 
     */
    public synchronized boolean isAnswerDetected() {
        if (!answerDetected) {
            answerDetected = true;
            return false;
        }
        return true;
    }
    
    /**
     * Set variable answerDetected.
     * @param answerDetected 
     */
    public synchronized void setAnswerDetected(boolean answerDetected) {
        this.answerDetected = answerDetected;
    }
    
    /**
     * Return instance of SharedParameters. If instance with this taskId already
     * exist, then method will return it.
     * @param taskId
     * ID of outbound calls task.
     * @return 
     * New instance or already existing instance of SharedParameters class.
     */
    public static SharedParameters getSharedParametersInstance(String taskId) {
        
        SharedParameters instance = instances.get(taskId);
        
        if (instance == null) {
            instance = new SharedParameters();
            instances.put(taskId, instance);
        }
        
        return instance;
    }
}