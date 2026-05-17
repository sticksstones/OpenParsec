package com.example.parsecdemo;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.opengles.GL10;

import parsec.bindings.Parsec;

/**
 * GLSurfaceView wrapper that:
 *  - renders Parsec stream
 *  - exposes Touchpad and Direct input modes (OpenParsec-equivalent)
 *  - reports a local visual cursor position to a TrackpadListener for an
 *    Android overlay view that traverses the entire visible viewport
 *  - maps client coordinates to host coordinates with the OpenParsec
 *    aspect-ratio (letterbox) math so the host cursor can reach the
 *    edges of the rendered frame even when aspects mismatch
 */
public class ClientGLSurface extends GLSurfaceView {
    public interface TrackpadListener {
        void onTrackpadCursorChanged(float x, float y, boolean visible);
    }

    private Parsec parsec;
    private final Object parsecLock = new Object();
    private volatile boolean parsecAlive = false;

    private volatile int surfaceWidth = 0;
    private volatile int surfaceHeight = 0;

    private boolean trackpadMode = true;
    private float sensitivity = 1.6f;
    /** Set by external mouse-button row: when any virtual mouse button is held,
     *  the trackpad surface should NOT generate tap-clicks (we're in drag mode). */
    private volatile boolean externalButtonHeld = false;
    /** When middle-button scroll mode is active, vertical finger movement on the
     *  trackpad is translated into mouse wheel events instead of cursor motion. */
    private volatile boolean scrollMode = false;
    private float scrollAccum = 0f;
    private static final float SCROLL_PX_PER_TICK = 24f;
    private float cursorX = 0f;
    private float cursorY = 0f;
    private float lastX = 0f;
    private float lastY = 0f;
    private float downX = 0f;
    private float downY = 0f;
    private long downTime = 0L;
    private static final float TAP_SLOP_PX = 12f;
    private static final long TAP_MAX_MS = 180L;

    private int hostWidth = 1920;
    private int hostHeight = 1080;

    private TrackpadListener trackpadListener;

    public ClientGLSurface(Context context) {
        super(context);
    }

    public void setParsec(Parsec parsec) {
        synchronized (parsecLock) {
            this.parsec = parsec;
            this.parsecAlive = parsec != null;
        }
    }

    public void setTrackpadListener(TrackpadListener l) {
        this.trackpadListener = l;
    }

    public void setTrackpadMode(boolean enabled) {
        this.trackpadMode = enabled;
        if (enabled) {
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                cursorX = surfaceWidth / 2f;
                cursorY = surfaceHeight / 2f;
                sendAbsoluteMotionMapped(cursorX, cursorY);
                notifyCursor(true);
            }
        } else {
            notifyCursor(false);
        }
    }

    public boolean isTrackpadMode() { return trackpadMode; }

    public void setSensitivity(float v) { this.sensitivity = Math.max(0.1f, v); }

    /** When true, suppress the auto-tap-to-click behaviour so the user can hold
     *  an external mouse button and drag with another finger. */
    public void setExternalButtonHeld(boolean held) { this.externalButtonHeld = held; }

    /** When true, vertical drags on the trackpad are translated to wheel ticks. */
    public void setScrollMode(boolean on) {
        this.scrollMode = on;
        this.scrollAccum = 0f;
    }

    /** Press or release a specific mouse button (used by the on-screen button row). */
    public void sendButtonExternal(int parsecButton, boolean pressed) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseButton(parsecButton, pressed);
        }
    }

    public void setHostDimensions(int w, int h) {
        if (w > 0) this.hostWidth = w;
        if (h > 0) this.hostHeight = h;
    }

    /** Called by ParsecActivity when the visible view is resized (fold/unfold, rotation).
     *  Note: this does NOT push dimensions to Parsec directly — the GL renderer's
     *  onSurfaceChanged is the authoritative source and runs against the actual
     *  EGL surface size. Passing root dimensions here would race with that and
     *  could leave the host renderer with the wrong viewport (off-center). */
    public void onClientResize(int width, int height) {
        // Only update bookkeeping if it isn't already matching the GL side.
        if (surfaceWidth != width || surfaceHeight != height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }
        // Re-center cursor to the new viewport
        cursorX = width / 2f;
        cursorY = height / 2f;
        if (trackpadMode) notifyCursor(true);
    }

    /** Wipe transient input state so a fold/unfold (which can drop touch events
     *  mid-gesture) doesn't leave us with phantom held buttons, stuck scroll
     *  mode, or a frozen finger position. Releases all mouse buttons on the
     *  host side, clears scroll accumulation, and re-centers the cursor. */
    public void resetTouchState() {
        scrollMode = false;
        scrollAccum = 0f;
        externalButtonHeld = false;
        downTime = 0L;
        downX = downY = 0f;
        lastX = lastY = 0f;
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) {
                // Release all standard mouse buttons defensively.
                parsec.clientSendMouseButton(1 /* MOUSE_L */, false);
                parsec.clientSendMouseButton(2 /* MOUSE_MIDDLE */, false);
                parsec.clientSendMouseButton(3 /* MOUSE_R */, false);
            }
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            cursorX = surfaceWidth / 2f;
            cursorY = surfaceHeight / 2f;
            if (trackpadMode) {
                sendAbsoluteMotionMapped(cursorX, cursorY);
                notifyCursor(true);
            }
        }
    }

    public void renderInit() {
        setEGLContextClientVersion(2);
        setRenderer(new Renderer() {
            @Override public void onSurfaceCreated(GL10 gl10, javax.microedition.khronos.egl.EGLConfig eglConfig) {}
            @Override public void onSurfaceChanged(GL10 gl10, int width, int height) {
                surfaceWidth = width;
                surfaceHeight = height;
                if (cursorX == 0f && cursorY == 0f) {
                    cursorX = width / 2f;
                    cursorY = height / 2f;
                }
                synchronized (parsecLock) {
                    if (parsecAlive && parsec != null) parsec.clientSetDimensions(width, height);
                }
                if (trackpadMode) notifyCursor(true);
            }
            @Override public void onDrawFrame(GL10 gl10) {
                synchronized (parsecLock) {
                    if (!parsecAlive || parsec == null) return;
                    parsec.clientPollAudio();
                    parsec.clientGLRenderFrame();
                }
            }
        });
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void shutdown() {
        synchronized (parsecLock) {
            parsecAlive = false;
            parsec = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);
        if (!parsecAlive) return true;
        if (trackpadMode) return onTrackpadEvent(ev);
        return onDirectTouchEvent(ev);
    }

    private boolean onDirectTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                cursorX = x; cursorY = y;
                sendAbsoluteMotionMapped(x, y);
                sendButton(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                cursorX = x; cursorY = y;
                sendAbsoluteMotionMapped(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cursorX = x; cursorY = y;
                sendAbsoluteMotionMapped(x, y);
                sendButton(false);
                return true;
        }
        return true;
    }

    private boolean onTrackpadEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                downX = lastX;
                downY = lastY;
                downTime = ev.getEventTime();
                notifyCursor(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - lastX;
                float dy = ev.getY() - lastY;
                lastX = ev.getX();
                lastY = ev.getY();
                if (scrollMode) {
                    // Accumulate vertical motion into wheel ticks.
                    scrollAccum += dy;
                    int ticks = (int) (scrollAccum / SCROLL_PX_PER_TICK);
                    if (ticks != 0) {
                        scrollAccum -= ticks * SCROLL_PX_PER_TICK;
                        // Parsec wheel: positive y = scroll down (page moves up).
                        // Drag down should scroll down → ticks already match.
                        sendWheel(0, ticks * 120);
                    }
                } else {
                    cursorX = clamp(cursorX + dx * sensitivity, 0, surfaceWidth - 1);
                    cursorY = clamp(cursorY + dy * sensitivity, 0, surfaceHeight - 1);
                    sendAbsoluteMotionMapped(cursorX, cursorY);
                    notifyCursor(true);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                long dt = ev.getEventTime() - downTime;
                float totalDx = ev.getX() - downX;
                float totalDy = ev.getY() - downY;
                boolean moved = (totalDx * totalDx + totalDy * totalDy) > TAP_SLOP_PX * TAP_SLOP_PX;
                // Tap-to-click is suppressed while an external mouse button is held
                // (the user is mid-click-drag) and while in scroll mode.
                if (!moved && dt <= TAP_MAX_MS && !externalButtonHeld && !scrollMode) {
                    sendButton(true);
                    sendButton(false);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return true;
    }

    private float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Absolute mouse motion in Parsec's SDK is interpreted in the client-window
     * coordinate space configured via ParsecClientSetDimensions — NOT the host's
     * native desktop resolution. Parsec handles the host-side mapping internally,
     * so we just forward the raw client coordinates to get 1:1 behaviour.
     */
    private void sendAbsoluteMotionMapped(float clientX, float clientY) {
        int w = surfaceWidth > 0 ? surfaceWidth : 1;
        int h = surfaceHeight > 0 ? surfaceHeight : 1;
        int ix = (int) clamp(clientX, 0, w - 1);
        int iy = (int) clamp(clientY, 0, h - 1);
        sendAbsoluteMotion(ix, iy);
    }

    private void sendAbsoluteMotion(int x, int y) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseMotion(false, x, y);
        }
    }

    private void sendButton(boolean pressed) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseButton(parsec.MOUSE_L, pressed);
        }
    }

    private void sendWheel(int x, int y) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseWheel(x, y);
        }
    }

    private void notifyCursor(final boolean visible) {
        if (trackpadListener == null) return;
        final float x = cursorX;
        final float y = cursorY;
        post(new Runnable() {
            @Override public void run() {
                if (trackpadListener != null) trackpadListener.onTrackpadCursorChanged(x, y, visible);
            }
        });
    }
}
