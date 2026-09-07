package io.github.onaiaku.artmoon.artlight;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.onaiaku.artmoon.LimeLog;

import java.util.ArrayList;
import java.util.List;

/**
 * InputModeManager — app-wide tracker of the last input method used
 * (touch / gamepad / keyboard), with per-screen prompt refresh callbacks.
 *
 * The desktop ArtMoon tells the truth about the input device: prompts render
 * as controller glyphs when a pad is driving the UI and as key labels when a
 * keyboard is. Android previously baked "S"/"Esc" keyboard labels into the
 * footer, which lied to touch and controller users alike. This manager is
 * the single source of truth; every screen with a prompt bar registers its
 * refresh callback here and re-renders when the mode changes.
 *
 * Detection: Activity.dispatch* hooks call notifyTouchEvent / notifyKeyEvent;
 * a KeyEvent from a device with a gamepad sources classifies as gamepad even
 * though it arrives as a KeyEvent (D-pad, A/B/X/Y).
 */
public final class InputModeManager {

    public enum Mode { TOUCH, GAMEPAD, KEYBOARD }

    public interface PromptRefreshListener {
        /** Called when the input mode changed; re-render this screen's prompts. */
        void onInputModeChanged(Mode newMode);
    }

    private static final InputModeManager INSTANCE = new InputModeManager();

    public static InputModeManager get() {
        return INSTANCE;
    }

    private InputModeManager() {
    }

    private Mode mode = Mode.TOUCH; // touch is the Android default
    private final List<PromptRefreshListener> listeners = new ArrayList<>();

    public Mode getMode() {
        return mode;
    }

    public void addListener(PromptRefreshListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(PromptRefreshListener l) {
        listeners.remove(l);
    }

    private void setMode(Mode newMode) {
        if (newMode == mode) {
            return;
        }
        mode = newMode;
        LimeLog.info("InputModeManager: mode -> " + newMode);
        // Copy: listeners may remove themselves during iteration
        for (PromptRefreshListener l : new ArrayList<>(listeners)) {
            l.onInputModeChanged(mode);
        }
    }

    /** Wire from Activity.dispatchTouchEvent(). */
    public void notifyTouchEvent() {
        setMode(Mode.TOUCH);
    }

    /**
     * Wire from Activity.dispatchKeyEvent(). Returns the event unchanged
     * (pure observer). A key from a source with the GAMEPAD class bit marks
     * gamepad mode; any other hardware key (keyboard, remote) marks keyboard.
     */
    public KeyEvent notifyKeyEvent(KeyEvent event) {
        if (event == null) {
            return event;
        }
        InputDevice dev = InputDevice.getDevice(event.getDeviceId());
        boolean isGamepad = dev != null
                && (dev.getSources() & InputDevice.SOURCE_CLASS_BUTTON) != 0
                && (dev.getSources() & InputDevice.SOURCE_GAMEPAD) != 0;
        // A controller's D-pad arrives with SOURCE_DPAD/DPAD_CLASSIFIER; treat
        // those as gamepad too (same UI implications: focus nav + glyphs).
        boolean isDpad = dev != null
                && (dev.getSources() & InputDevice.SOURCE_DPAD) != 0;
        // Device lookup can fail (BT reconnects, virtual/emulated pads, some
        // TV remotes). The event itself always carries its true source, so
        // trust it as an equal signal — never demote a gamepad press to
        // KEYBOARD just because the device record went missing.
        int src = event.getSource();
        boolean srcGamepad = (src & InputDevice.SOURCE_GAMEPAD) != 0
                || (src & InputDevice.SOURCE_DPAD) != 0;
        Mode oldMode = mode;
        if (isGamepad || isDpad || srcGamepad) {
            setMode(Mode.GAMEPAD);
        } else if (event.getSource() != InputDevice.SOURCE_UNKNOWN) {
            setMode(Mode.KEYBOARD);
        }
        android.util.Log.d("PadFocus", "classify kc=" + event.getKeyCode()
                + " act=" + event.getAction()
                + " devOk=" + (dev != null)
                + " srcGamepad=" + srcGamepad
                + " mode=" + oldMode + "->" + mode);
        return event;
    }
}
