/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package autodropoutboundcalls;

/**
 *
 * @author zh
 */
class SharedParameters {
    
    private boolean answerDetected = false;

    public synchronized boolean isAnswerDetected() {
        if (!answerDetected) {
            answerDetected = true;
            return false;
        }
        return true;
    }

    public synchronized void setAnswerDetected(boolean answerDetected) {
        this.answerDetected = answerDetected;
    }
}
