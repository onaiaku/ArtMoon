package io.github.onaiaku.artmoon.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    /*
     * §446 — the host's answer to a launch that is not an error (5.9.0 on the desktop:
     * AppsScreen.qml:446, decided in session.cpp:2258).
     *
     * A 410 from the launch request means one of two things, and only the wording tells them
     * apart: a host control that finished without a stream (Terminate, Disconnect Monitor,
     * Disconnect Input) answering with a success message, or an action the server wants
     * confirmed — launch the same entry again within 60 seconds. `confirm` is that distinction,
     * and it is worked out where the status is read, not here.
     *
     * Not a third kind of displayMessage: that one is a toast for the failures, and this is the
     * host talking to the person holding the phone.
     */
    void launchNotice(String message, boolean confirm);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);
}
