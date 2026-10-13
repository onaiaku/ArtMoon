package io.github.onaiaku.artmoon.artlight;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PromptBar — footer prompt pills that always show KEY + NAME, with the key
 * half rendered per input mode (Nik's spec, desktop parity):
 *
 *   TOUCH     → name only ("Shutdown"), keycap hidden — the pill IS the button
 *   KEYBOARD  → [P] Shutdown   (flat keycap chip with the key letter)
 *   GAMEPAD   → (Y) Settings   (coloured Xbox face button + name)
 *
 * Each screen registers (keycapView, labelView, action) triples. The keycap
 * is shown/hidden and re-skinned on mode change; the label always keeps its
 * real name — labels are never overwritten.
 */
public final class PromptBar {

    /** One prompt: the keycap chip and the label next to it. */
    public static class Pill {
        public final TextView keycap;
        public final TextView label;
        /** Semantic action: "settings" | "shutdown" | "exit" | "hosts" | "back" */
        public final String action;

        public Pill(TextView keycap, TextView label, String action) {
            this.keycap = keycap;
            this.label = label;
            this.action = action;
        }
    }

    private final Map<String, Pill> pills = new LinkedHashMap<>();
    /**
     * A word the page has moved for a prompt that follows the row rather than the action.
     *
     * 6.0.0: the desktop's pin prompt says Unpin on a pinned game and Pin on any other
     * (AppShell.qml:539-545) — one action, two words, and only the page knows which, because
     * only the page knows the row under the cursor. Held here so a mode change restores the
     * right word instead of snapping back to the action's default.
     */
    private final java.util.Map<String, String> labelOverrides = new java.util.HashMap<String, String>();

    // Resting state per action, restored when leaving gamepad mode
    private final Map<String, android.graphics.drawable.Drawable> origKeycapBg = new LinkedHashMap<>();
    private final Map<String, Integer> origKeycapColor = new LinkedHashMap<>();
    private final Map<String, int[]> origKeycapPad = new LinkedHashMap<>();
    private final Map<String, Integer> origKeycapGravity = new LinkedHashMap<>();
    private final Activity activity;
    private boolean registered = false;

    public PromptBar(Activity activity) {
        this.activity = activity;
        InputModeManager.get().addListener(new InputModeManager.PromptRefreshListener() {
            @Override
            public void onInputModeChanged(InputModeManager.Mode newMode) {
                refresh(newMode);
            }
        });
    }

    /** Register a keycap+label pair. Either view may be null (null-safe). */
    public void register(TextView keycap, TextView label, String action) {
        if (keycap == null && label == null) {
            return;
        }
        if (keycap != null && !origKeycapBg.containsKey(action)) {
            origKeycapBg.put(action, keycap.getBackground());
            origKeycapColor.put(action, keycap.getCurrentTextColor());
            // A glyph replaces the keycap's TEXT, and the resting chip is sized by
            // that text plus its padding. Saving the background alone would put a
            // glyph back on a chip that still had no room around the letter.
            origKeycapPad.put(action, new int[] {
                    keycap.getPaddingLeft(), keycap.getPaddingTop(),
                    keycap.getPaddingRight(), keycap.getPaddingBottom() });
            origKeycapGravity.put(action, keycap.getGravity());
        }
        pills.put(action, new Pill(keycap, label, action));
    }

    /** Register a keycap on its own — a prompt whose label is not a view of its own. */
    public void registerById(int keycapId, String action) {
        View k = activity.findViewById(keycapId);
        register(k instanceof TextView ? (TextView) k : null, null, action);
    }

    /** Register by view id lookup (null-safe — layouts may lack pills). */
    public void registerById(int keycapId, int labelId, String action) {
        View k = activity.findViewById(keycapId);
        View l = activity.findViewById(labelId);
        register(k instanceof TextView ? (TextView) k : null,
                 l instanceof TextView ? (TextView) l : null,
                 action);
    }

    /** First paint + mark as live. Call after all registrations. */
    public void attach() {
        registered = true;
        refresh(InputModeManager.get().getMode());
    }

    public boolean isAttached() {
        return registered;
    }

    private void refresh(InputModeManager.Mode mode) {
        if (!registered) {
            return;
        }
        for (Pill p : pills.values()) {
            boolean gamepad = mode == InputModeManager.Mode.GAMEPAD;

            if (p.label != null) {
                // Label always shows the real name — restore the string
                // resource in case anything ever overwrote it.
                p.label.setText(labelTextFor(p.action));
            }

            if (p.keycap == null) {
                continue;
            }
            if (mode == InputModeManager.Mode.TOUCH) {
                // ⚠️ TOUCH USED TO HIDE THE CHIP, and that is the divergence this screen was
                // showing: a footer prompt drawn as the WORD ALONE, in a capsule.
                //
                // The desktop has no such mode. ActionHint picks between the pad glyph and the
                // key chip (ActionHint.qml:34) and there is no third option that draws nothing —
                // AppShell gives every prompt both a button and a key (AppShell.qml:534-561), and
                // the chip is the half that says which one it is. On a touchscreen it is also the
                // only part of the prompt that looks pressable.
                //
                // So touch draws what a keyboard draws, and the word beside it goes back to
                // being a plain word.
                restoreKeycap(p);
                p.keycap.setText(textFor(mode, p.action));
                p.keycap.setVisibility(View.VISIBLE);
            } else {
                p.keycap.setVisibility(View.VISIBLE);
                if (gamepad) {
                    // The real vendor glyph: circle and symbol in one image, the
                    // same art the desktop draws. It carries its own letter, so the
                    // keycap shows the picture and no text at all.
                    int glyph = PadGlyphs.drawableFor(
                            InputModeManager.get().getGlyphSet(), buttonKeyFor(p.action));
                    android.graphics.drawable.Drawable d = glyph != 0
                            ? PadGlyphs.sized(activity.getResources(), glyph, capFacePx(p.keycap)) : null;
                    if (d != null) {
                        p.keycap.setText("");
                        p.keycap.setCompoundDrawables(d, null, null, null);
                        p.keycap.setGravity(android.view.Gravity.CENTER);
                        p.keycap.setPadding(0, 0, 0, 0);
                        p.keycap.setBackground(null);
                    } else {
                        // No glyph for this button: show the key letter rather than
                        // leaving the prompt blank.
                        restoreKeycap(p);
                        p.keycap.setText(textFor(mode, p.action));
                    }
                } else { // keyboard
                    restoreKeycap(p);
                    p.keycap.setText(textFor(mode, p.action));
                }
            }
        }
    }

    /**
     * The glyph's box in pixels: GLYPH_DP through the same scale as the screen around it, and
     * never taller than the cap it is going into.
     *
     * The desktop draws the glyph at _px(26) inside a _px(44) row — both from one uiScale
     * (AppShell.qml:601 and :470). The Android tree gets that same factor through
     * AmScale.applyTo, so the footer cap is 24dp × uiScale — 35px on the Shield at uiScale 0.72.
     * PadGlyphs sized the glyph from density alone, which left it at 22dp = 44px inside a 35px
     * cap: the circle's top and bottom were both cut off, which is exactly what the Shield
     * showed on 13 Oct 2026.
     *
     * The cap's own height is the final word deliberately. A glyph that does not fit its cap is
     * broken on every device, whatever the scale says — and getHeight() is 0 before the first
     * layout pass, so the scaled number stands there.
     */
    private int capFacePx(TextView cap) {
        int face = io.github.onaiaku.artmoon.ui.AmScale.ofScreen(activity)
                .lengthPx(PadGlyphs.GLYPH_DP, activity);
        int room = cap.getHeight() - cap.getPaddingTop() - cap.getPaddingBottom();
        if (room > 0 && room < face) {
            face = room;
        }
        return face;
    }

    /** Undo everything gamepad mode did to a keycap, so text mode is untouched. */
    private void restoreKeycap(Pill p) {
        p.keycap.setCompoundDrawables(null, null, null, null);
        android.graphics.drawable.Drawable bg = origKeycapBg.get(p.action);
        if (bg != null) {
            p.keycap.setBackground(bg);
        }
        Integer col = origKeycapColor.get(p.action);
        if (col != null) {
            p.keycap.setTextColor(col);
        }
        int[] pad = origKeycapPad.get(p.action);
        if (pad != null) {
            p.keycap.setPadding(pad[0], pad[1], pad[2], pad[3]);
        }
        Integer grav = origKeycapGravity.get(p.action);
        if (grav != null) {
            p.keycap.setGravity(grav);
        }
    }

    /**
     * Gamepad mode draws a glyph, not text, so this is only reached when no glyph
     * exists for the button — the key letter is the honest thing to show then.
     */
    private static String textFor(InputModeManager.Mode mode, String action) {
        return keyboardKey(action);
    }

    /**
     * The pad button a prompt names, from the desktop's own hint row
     * (app/gui/AppShell.qml, _hintsHome): Power is X, Settings is Y, Exit is B.
     * Anything unlisted selects with A.
     *
     * Android used to invent a "power paddle" here — a flat grey circle with an
     * arrow typed on it — where the desktop has always put X. The keyboard
     * letters (P / S / Esc) were already right, which is why they are untouched.
     */
    private static String buttonKeyFor(String action) {
        if ("settings".equals(action)) {
            return "Y";
        }
        if ("shutdown".equals(action) || "stop".equals(action)) {
            return "X";
        }
        if ("exit".equals(action) || "back".equals(action) || "hosts".equals(action)) {
            return "B";
        }
        // The picker's two shoulders, drawn at the ends of its tab strip (AppsScreen.qml:1226-1246
        // and the same pair on Settings, AppShell.qml _hintsSettings).
        // Two strips, two different pairs, and the desktop keeps them apart on purpose.
        // The app picker's library tabs are the SHOULDERS (AppsScreen.qml:1226). The home
        // strip is the TRIGGERS — sdlgamepadkeynavigation.cpp:521-556, "Triggers → previous /
        // next host on the Home screen" — and the shoulders there cycle the host's streaming
        // profile instead (F16/F17).
        if ("prevTab".equals(action)) {
            return "LB";
        }
        if ("nextTab".equals(action)) {
            return "RB";
        }
        // ⚠️ THE HOST STRIP IS THE SHOULDERS ON THE PHONE — Nik's call, not a reading of the
        // desktop. The desktop prints LT/RT at the ends of that strip (HomeScreen.qml:1853, 1949)
        // and pages with the trigger AXES, edge-detected with hysteresis
        // (sdlgamepadkeynavigation.cpp:525-554). On Android that is a pair that does not arrive:
        // a trigger is delivered as AXIS_LTRIGGER / AXIS_RTRIGGER motion events on many pads, never
        // as KEYCODE_BUTTON_L2 / R2, so there is no key event to handle — which is exactly what
        // "they dont do anything" looks like from the other side of the screen. The shoulders are
        // buttons on every pad. His words, 12 Oct 2026: "they sould be LB and RB".
        //
        // The keyboard half is untouched and unchanged: PgUp/PgDn (keyboardKey below), the same
        // pair the desktop prints on those arrows.
        if ("prevHost".equals(action)) {
            return "LB";
        }
        if ("nextHost".equals(action)) {
            return "RB";
        }
        // 6.0.0, the desktop's own hint row (AppShell.qml:533-545): Start / P pins the selected
        // game. Start, not Select — Select is the per-game Customize on that page.
        if ("pin".equals(action)) {
            return "START";
        }
        // 6.1.0, the same hint row (AppShell.qml:547-548): btn "RS", key "M". The right stick, not
        // a shoulder — the shoulders belong to this page's tabs.
        if ("move".equals(action)) {
            return "RS";
        }
        return "A";
    }

    private static String keyboardKey(String action) {
        if ("settings".equals(action)) {
            return "S";
        }
        if ("shutdown".equals(action)) {
            return "P";
        }
        if ("stop".equals(action)) {
            return "X";
        }
        if ("exit".equals(action) || "back".equals(action) || "hosts".equals(action)) {
            return "Esc";
        }
        if ("prevTab".equals(action) || "prevHost".equals(action)) {
            return "PgUp";
        }
        if ("nextTab".equals(action) || "nextHost".equals(action)) {
            return "PgDn";
        }
        if ("pin".equals(action)) {
            return "P";
        }
        if ("move".equals(action)) {
            return "M";
        }
        return "Enter";
    }

    /** Moves a prompt's word for a state the page owns. Null puts the action's own word back. */
    public void setLabel(String action, String text) {
        if (text == null) {
            labelOverrides.remove(action);
        }
        else {
            labelOverrides.put(action, text);
        }
        final Pill p = pills.get(action);
        if (p != null && p.label != null) {
            p.label.setText(labelTextFor(action));
        }
    }

    private String labelTextFor(String action) {
        final String over = labelOverrides.get(action);
        return over != null ? over : labelFor(action);
    }

    private static String labelFor(String action) {
        if ("shutdown".equals(action)) {
            return "Shutdown";
        }
        if ("settings".equals(action)) {
            return "Settings";
        }
        if ("hosts".equals(action)) {
            return "Hosts";
        }
        if ("stop".equals(action)) {
            return "Stop";
        }
        if ("exit".equals(action) || "back".equals(action)) {
            return "Exit";
        }
        if ("pin".equals(action)) {
            return "Pin";
        }
        if ("move".equals(action)) {
            return "Move";
        }
        return action;
    }
}
