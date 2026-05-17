package com.example.parsecdemo;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sticky modifier + special-key row that attaches above the soft keyboard.
 *
 * Modifiers (Ctrl, Alt, ⊞ / Cmd, Shift) latch:
 *  - single tap → armed (one-shot, auto-releases after the next key press)
 *  - long-press → locked (stays held until tapped again)
 *
 * Special keys (Esc, Tab, arrow keys, Ins, Home, End, PgUp, PgDn, Del, F1–F12)
 * send a press+release immediately with the currently armed modifiers applied.
 *
 * Mac aliases: ⌃ Control = Ctrl, ⌥ Option = Alt, ⌘ Cmd = ⊞ (Windows/GUI key).
 */
public final class ImeAccessoryBar extends FrameLayout {

    public interface Listener {
        /** Send press+release for {@code parsecKey} with modifier state from the bar. */
        void onSpecialKey(int parsecKey);
        /** Send a raw modifier press or release (no wrapping). Used by single-tap of
         *  a modifier button — tap fires {@code press=true} then {@code press=false}
         *  immediately so e.g. tapping ⊞ alone opens the Windows Start menu. */
        void onModifierTap(int parsecKey);
        /** Notify the bar's modifier state has changed (for the keyboard capture path). */
        void onModifiersChanged(int modBitmask);
    }

    public static final int MOD_CTRL  = 1 << 0;
    public static final int MOD_ALT   = 1 << 1;
    public static final int MOD_SHIFT = 1 << 2;
    public static final int MOD_META  = 1 << 3; // Windows / Cmd

    // Parsec keycodes for left-side modifiers
    private static final int PK_LCTRL  = 224;
    private static final int PK_LSHIFT = 225;
    private static final int PK_LALT   = 226;
    private static final int PK_LGUI   = 227;

    private final Listener listener;

    private int armed = 0;   // one-shot — cleared after next non-modifier key
    private int locked = 0;  // stays on until tapped again (long-press)

    // Buttons that visualize the modifier state
    private ModButton ctrlBtn, altBtn, shiftBtn, metaBtn;

    public ImeAccessoryBar(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;

        // Surface-container background, no rounded corners (sits flush above IME)
        setBackgroundColor(MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        row.setPadding(pad, pad, pad, pad);

        ctrlBtn  = addModifier(row, "Ctrl",  MOD_CTRL);
        altBtn   = addModifier(row, "Alt",   MOD_ALT);
        metaBtn  = addModifier(row, "⊞",     MOD_META);
        shiftBtn = addModifier(row, "Shift", MOD_SHIFT);

        addSeparator(row);

        addAction(row, "Esc",  KeyMap.KEY_ESCAPE);
        addAction(row, "Tab",  KeyMap.KEY_TAB);
        addAction(row, "←",    KeyMap.KEY_LEFT);
        addAction(row, "↑",    KeyMap.KEY_UP);
        addAction(row, "↓",    KeyMap.KEY_DOWN);
        addAction(row, "→",    KeyMap.KEY_RIGHT);
        addAction(row, "Home", KeyMap.KEY_HOME);
        addAction(row, "End",  KeyMap.KEY_END);
        addAction(row, "PgUp", KeyMap.KEY_PAGEUP);
        addAction(row, "PgDn", KeyMap.KEY_PAGEDOWN);
        addAction(row, "Ins",  73 /* KEY_INSERT */);
        addAction(row, "Del",  KeyMap.KEY_DELETE);

        addSeparator(row);

        // Function keys F1..F12 (Parsec codes 58..69)
        for (int i = 0; i < 12; i++) {
            addAction(row, "F" + (i + 1), 58 + i);
        }

        scroll.addView(row);
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Current armed | locked modifier bitmask. */
    public int currentModifiers() { return armed | locked; }

    /** Clear all latched (armed + locked) modifier state. Intended to be
     *  called from the activity when something external happened that may
     *  have left the host with a stuck modifier (fold/unfold, IME reset). */
    public void clearLatchedModifiers() {
        armed = 0;
        locked = 0;
        refreshButtons();
        listener.onModifiersChanged(0);
    }

    /** Called by ParsecActivity after a non-modifier key was sent: clears one-shot modifiers. */
    public void consumeArmedModifiers() {
        if (armed != 0) {
            armed = 0;
            refreshButtons();
            listener.onModifiersChanged(currentModifiers());
        }
    }

    private ModButton addModifier(LinearLayout row, String label, final int bit) {
        ModButton b = new ModButton(getContext(), label);
        final int parsecKey = parsecKeyForBit(bit);
        b.setOnClickListener(v -> {
            // Short tap: if currently locked, unlock (release). Otherwise send a
            // momentary press+release immediately so e.g. tapping ⊞ alone opens
            // the Windows Start menu. Chording is done via long-press (locks).
            if ((locked & bit) != 0) {
                locked &= ~bit;
                refreshButtons();
                listener.onModifiersChanged(currentModifiers());
            } else {
                listener.onModifierTap(parsecKey);
            }
        });
        b.setOnLongClickListener(v -> {
            // Long press: toggle locked state for chording. Locked modifiers are
            // applied to every action-key tap and to every char typed via the
            // soft keyboard until the user taps the modifier again.
            locked ^= bit;
            armed &= ~bit;
            refreshButtons();
            listener.onModifiersChanged(currentModifiers());
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        row.addView(b, lp);
        return b;
    }

    private static int parsecKeyForBit(int bit) {
        switch (bit) {
            case MOD_CTRL:  return PK_LCTRL;
            case MOD_ALT:   return PK_LALT;
            case MOD_SHIFT: return PK_LSHIFT;
            case MOD_META:  return PK_LGUI;
            default:        return 0;
        }
    }

    private void addAction(LinearLayout row, String label, final int parsecKey) {
        ModButton b = new ModButton(getContext(), label);
        b.setOnClickListener(v -> {
            listener.onSpecialKey(parsecKey);
            // after a key press, one-shot modifiers are cleared via consumeArmedModifiers()
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        row.addView(b, lp);
    }

    private void addSeparator(LinearLayout row) {
        View v = new View(getContext());
        v.setBackgroundColor(MaterialUi.color(getContext(),
                com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(24));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        row.addView(v, lp);
    }

    private void refreshButtons() {
        ctrlBtn.setState((armed & MOD_CTRL) != 0, (locked & MOD_CTRL) != 0);
        altBtn.setState((armed & MOD_ALT) != 0, (locked & MOD_ALT) != 0);
        shiftBtn.setState((armed & MOD_SHIFT) != 0, (locked & MOD_SHIFT) != 0);
        metaBtn.setState((armed & MOD_META) != 0, (locked & MOD_META) != 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Pill-shaped key with tri-state visual: off / armed / locked. */
    private final class ModButton extends FrameLayout {
        private final GradientDrawable bg;
        private final TextView label;
        private final int idleBgColor, idleFgColor;
        private final int armedBgColor, armedFgColor;
        private final int lockedBgColor, lockedFgColor;

        ModButton(Context ctx, String text) {
            super(ctx);
            idleBgColor   = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest);
            idleFgColor   = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorOnSurface);
            armedBgColor  = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorPrimaryContainer);
            armedFgColor  = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorOnPrimaryContainer);
            lockedBgColor = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorPrimary);
            lockedFgColor = MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);

            bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(idleBgColor);
            bg.setCornerRadius(dp(18));
            setBackground(bg);
            setClickable(true);
            setLongClickable(true);
            // Crucially, do NOT make the button focusable in touch mode.
            // Doing so would transfer focus away from the hidden keyboardCapture
            // EditText, and the soft keyboard would then have no target — typed
            // characters would disappear into the void.
            setFocusable(false);
            setFocusableInTouchMode(false);

            label = new TextView(ctx);
            label.setText(text);
            label.setTextColor(idleFgColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            int hp = dp(14);
            int vp = dp(6);
            label.setPadding(hp, vp, hp, vp);
            addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        }

        void setState(boolean armedState, boolean lockedState) {
            if (lockedState) {
                bg.setColor(lockedBgColor);
                label.setTextColor(lockedFgColor);
            } else if (armedState) {
                bg.setColor(armedBgColor);
                label.setTextColor(armedFgColor);
            } else {
                bg.setColor(idleBgColor);
                label.setTextColor(idleFgColor);
            }
        }
    }
}
