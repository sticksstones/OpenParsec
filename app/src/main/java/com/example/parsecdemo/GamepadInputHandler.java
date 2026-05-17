package com.example.parsecdemo;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import parsec.bindings.Parsec;

/**
 * Translates Android KeyEvent + MotionEvent from physical/integrated gamepads
 * (Xbox controllers, ROG Phone shoulders, Razer Edge sticks, etc.) into
 * Parsec gamepad messages. Pure static dispatcher — call from
 * Activity.dispatchKeyEvent / dispatchGenericMotionEvent.
 */
public final class GamepadInputHandler {

    /** Stable virtual gamepad id used for ALL physical gamepad input.
     *  Distinct from the virtual on-screen pad ({@code 1}) so a user with both
     *  doesn't have one pad masking the other on the host. */
    public static final int PHYSICAL_GAMEPAD_ID = 2;

    private GamepadInputHandler() {}

    /** Returns true if the given input device looks like a gamepad/joystick.
     *  Used to gate dispatch — non-gamepad sources are ignored. */
    public static boolean isGamepadSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Forward a KeyEvent to Parsec. Returns true if the event was consumed. */
    public static boolean handleKeyEvent(Parsec parsec, KeyEvent ev) {
        if (parsec == null) return false;
        if (!isGamepadSource(ev.getSource())) return false;
        int parsecBtn = mapKey(ev.getKeyCode());
        if (parsecBtn < 0) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID, parsecBtn, true);
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID, parsecBtn, false);
            return true;
        }
        return false;
    }

    /** Forward a generic MotionEvent (stick / trigger axis updates) to
     *  Parsec. Returns true if the event was consumed. */
    public static boolean handleMotionEvent(Parsec parsec, MotionEvent ev) {
        if (parsec == null) return false;
        if (!isGamepadSource(ev.getSource())) return false;
        if (ev.getAction() != MotionEvent.ACTION_MOVE) return false;

        InputDevice dev = ev.getDevice();
        // Left thumbstick
        sendAxis(parsec, dev, ev, MotionEvent.AXIS_X,  Parsec.GAMEPAD_AXIS_LX, false);
        sendAxis(parsec, dev, ev, MotionEvent.AXIS_Y,  Parsec.GAMEPAD_AXIS_LY, false);
        // Right thumbstick — Android exposes RX/RY on most controllers, but
        // some (older Xbox, certain phone controllers) use Z/RZ instead.
        // Try RX/RY first, fall back to Z/RZ if those axes don't exist.
        if (hasAxis(dev, MotionEvent.AXIS_RX) || hasAxis(dev, MotionEvent.AXIS_RY)) {
            sendAxis(parsec, dev, ev, MotionEvent.AXIS_RX, Parsec.GAMEPAD_AXIS_RX, false);
            sendAxis(parsec, dev, ev, MotionEvent.AXIS_RY, Parsec.GAMEPAD_AXIS_RY, false);
        } else {
            sendAxis(parsec, dev, ev, MotionEvent.AXIS_Z,  Parsec.GAMEPAD_AXIS_RX, false);
            sendAxis(parsec, dev, ev, MotionEvent.AXIS_RZ, Parsec.GAMEPAD_AXIS_RY, false);
        }

        // Triggers — Android may expose them as LTRIGGER/RTRIGGER (0..1) or
        // packed onto BRAKE/GAS. The trigger axes ride a 0..1 range, NOT
        // -1..1 like the sticks, so they get a different normalizer.
        sendTrigger(parsec, dev, ev,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
                Parsec.GAMEPAD_AXIS_TRIGGERL);
        sendTrigger(parsec, dev, ev,
                MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
                Parsec.GAMEPAD_AXIS_TRIGGERR);

        // HAT axes on many controllers represent the DPad. -1/0/+1.
        float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        dispatchHat(parsec, hatX, hatY);

        return true;
    }

    /** Disconnect the physical gamepad from the host (releases all buttons /
     *  zeroes axes). Call from onPause to avoid leaving "stuck" inputs on
     *  the host when the user backgrounds the app. */
    public static void unplug(Parsec parsec) {
        if (parsec == null) return;
        try { parsec.clientSendGamepadUnplug(PHYSICAL_GAMEPAD_ID); } catch (Throwable ignored) {}
    }

    // -------------------- internals --------------------

    private static boolean hasAxis(InputDevice dev, int axis) {
        if (dev == null) return false;
        InputDevice.MotionRange r = dev.getMotionRange(axis);
        return r != null;
    }

    private static void sendAxis(Parsec parsec, InputDevice dev, MotionEvent ev,
                                 int androidAxis, int parsecAxis, boolean invertY) {
        if (dev == null) return;
        InputDevice.MotionRange r = dev.getMotionRange(androidAxis);
        if (r == null) return;
        float raw = ev.getAxisValue(androidAxis);
        // Apply the device's reported flat (deadzone). Below deadzone → 0.
        float flat = r.getFlat();
        if (Math.abs(raw) < flat) raw = 0f;
        if (invertY) raw = -raw;
        int scaled = Math.round(raw * 32767f);
        if (scaled > 32767) scaled = 32767;
        if (scaled < -32768) scaled = -32768;
        parsec.clientSendGamepadAxis(PHYSICAL_GAMEPAD_ID, parsecAxis, scaled);
    }

    private static void sendTrigger(Parsec parsec, InputDevice dev, MotionEvent ev,
                                    int primaryAxis, int fallbackAxis, int parsecAxis) {
        if (dev == null) return;
        float raw = 0f;
        InputDevice.MotionRange r = dev.getMotionRange(primaryAxis);
        if (r != null) {
            raw = ev.getAxisValue(primaryAxis);
        } else {
            r = dev.getMotionRange(fallbackAxis);
            if (r != null) raw = ev.getAxisValue(fallbackAxis);
        }
        if (r == null) return;
        float flat = r.getFlat();
        if (raw < flat) raw = 0f;
        // Trigger range is 0..1 → scale to 0..32767 (Parsec triggers ride the
        // positive half of the int16 range; iOS reference does the same).
        int scaled = Math.round(raw * 32767f);
        if (scaled < 0) scaled = 0;
        if (scaled > 32767) scaled = 32767;
        parsec.clientSendGamepadAxis(PHYSICAL_GAMEPAD_ID, parsecAxis, scaled);
    }

    // Track previous HAT state so we emit press / release pairs only on edge.
    private static int lastHatX = 0, lastHatY = 0;

    private static void dispatchHat(Parsec parsec, float hx, float hy) {
        int hxI = hx > 0.5f ? 1 : (hx < -0.5f ? -1 : 0);
        int hyI = hy > 0.5f ? 1 : (hy < -0.5f ? -1 : 0);
        if (hxI != lastHatX) {
            if (lastHatX != 0) {
                parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID,
                        lastHatX > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_RIGHT
                                     : Parsec.GAMEPAD_BUTTON_DPAD_LEFT, false);
            }
            if (hxI != 0) {
                parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID,
                        hxI > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_RIGHT
                                : Parsec.GAMEPAD_BUTTON_DPAD_LEFT, true);
            }
            lastHatX = hxI;
        }
        if (hyI != lastHatY) {
            if (lastHatY != 0) {
                parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID,
                        lastHatY > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_DOWN
                                     : Parsec.GAMEPAD_BUTTON_DPAD_UP, false);
            }
            if (hyI != 0) {
                parsec.clientSendGamepadButton(PHYSICAL_GAMEPAD_ID,
                        hyI > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_DOWN
                                : Parsec.GAMEPAD_BUTTON_DPAD_UP, true);
            }
            lastHatY = hyI;
        }
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return Parsec.GAMEPAD_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: return Parsec.GAMEPAD_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X: return Parsec.GAMEPAD_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return Parsec.GAMEPAD_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return Parsec.GAMEPAD_BUTTON_LSHOULDER;
            case KeyEvent.KEYCODE_BUTTON_R1: return Parsec.GAMEPAD_BUTTON_RSHOULDER;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return Parsec.GAMEPAD_BUTTON_LSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return Parsec.GAMEPAD_BUTTON_RSTICK;
            case KeyEvent.KEYCODE_BUTTON_START: return Parsec.GAMEPAD_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Parsec.GAMEPAD_BUTTON_BACK;
            case KeyEvent.KEYCODE_BUTTON_MODE: return Parsec.GAMEPAD_BUTTON_GUIDE;
            // Some Android-as-gamepad sources fire DPad as discrete key events
            // (rather than HAT axis), so handle both paths.
            case KeyEvent.KEYCODE_DPAD_UP: return Parsec.GAMEPAD_BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Parsec.GAMEPAD_BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Parsec.GAMEPAD_BUTTON_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Parsec.GAMEPAD_BUTTON_DPAD_RIGHT;
            default: return -1;
        }
    }
}
