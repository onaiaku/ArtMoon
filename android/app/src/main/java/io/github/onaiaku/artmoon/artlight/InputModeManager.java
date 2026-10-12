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
        /**
         * Called when anything that decides how a prompt is DRAWN changed: the
         * input mode, or the vendor set behind the pad glyphs. Re-render this
         * screen's prompts.
         *
         * A pad swap is a prompt change even though the mode itself never moved
         * (gamepad -> gamepad), so this fires for both. That is why the callback
         * is named for the refresh rather than for the mode.
         */
        void onInputModeChanged(Mode newMode);
    }

    private static final InputModeManager INSTANCE = new InputModeManager();

    public static InputModeManager get() {
        return INSTANCE;
    }

    private InputModeManager() {
    }

    private Mode mode = Mode.TOUCH; // touch is the Android default
    private String glyphSet = PadGlyphs.UNKNOWN; // Xbox art until a pad says otherwise
    private final List<PromptRefreshListener> listeners = new ArrayList<>();

    public Mode getMode() {
        return mode;
    }

    /**
     * Which vendor's glyph art prompts should draw, resolved from the pad that is
     * actually being used: a PlayStation pad gets crosses and shapes, a Switch pad
     * gets its swapped buttons. Defaults to Xbox art, which is what the desktop
     * draws when it does not know either.
     */
    public String getGlyphSet() {
        return glyphSet;
    }

    private void setGlyphSet(String newSet) {
        if (newSet == null || newSet.equals(glyphSet)) {
            return;
        }
        glyphSet = newSet;
        LimeLog.info("InputModeManager: glyph set -> " + newSet);
        // Copy: listeners may remove themselves during iteration
        for (PromptRefreshListener l : new ArrayList<>(listeners)) {
            l.onInputModeChanged(mode);
        }
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
            // Vendor first, so the glyphs are right on the same frame the mode
            // flips — a pad press that draws Xbox art over a Nintendo layout is
            // worse than no glyph at all.
            //
            // Only when the device record survived: same reasoning as the mode
            // below. A missing record must not silently reset a known PlayStation
            // pad back to Xbox art.
            if (dev != null) {
                setGlyphSet(PadGlyphs.setForDevice(dev));
            }
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
