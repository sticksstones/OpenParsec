package com.example.parsecdemo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import parsec.bindings.Parsec;

public class ParsecActivity extends Activity {
    /** Diagnostic: when true, overlays a visible tile grid. */
    private static final boolean DEBUG_GRID = false;
    /** Stealth tile grid that keeps the SurfaceView routed through the window
     *  compositor (prevents the overlay-Views from being clipped beneath the
     *  GL surface on hardware-overlay-capable devices). Tiles are alpha=0x01,
     *  not visible to the eye but tracked by the compositor. */
    private static final boolean STEALTH_GRID = true;
    private static final int DEBUG_TILE_PX = 30;
    private static final int DEBUG_GAP_PX = 12;

    private Parsec parsec;
    private ClientGLSurface surface;
    private TextView statusView;
    private View cursorView;
    private FrameLayout root;
    private FrameLayout debugGrid;
    private SessionFab fab;
    private ImageButton keyboardButton;
    private EditText keyboardCapture;
    private boolean ignoreCaptureChange = false;
    private MouseButtonRow mouseButtonRow;
    private ImeAccessoryBar imeBar;
    private int imeAccessoryHeightPx;
    private int heldButtonCount = 0; // tracks how many virtual buttons are pressed
    private FrameLayout settingsOverlay;
    private Settings settings;
    /** When the IME pushes a user-positioned mouse row out of the way, we
     *  stash the original Y here and restore it when the IME closes. Null
     *  while the user-positioned row is in its normal place. */
    private Float mouseRowImeBackupY = null;

    /** Tracks the active gesture for the 4-finger recovery tap. We reset both
     *  the FAB and the mouse-button row when a single tap reaches 4 fingers
     *  down (so users can rescue them if they drift offscreen). */
    private int gestureMaxPointerCount = 0;
    private long gestureStartMs = 0L;
    private static final long FOUR_FINGER_TAP_WINDOW_MS = 500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // SOFT_INPUT_ADJUST_RESIZE causes WindowInsets.ime() to dispatch so
        // we can react when the soft keyboard opens.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Allow FAB shadow + cursor view to render at any edge of the viewport
        // (default FrameLayout clips children to its padding box).
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setFitsSystemWindows(false);

        statusView = new TextView(this);
        statusView.setText("Connecting…");
        statusView.setTextColor(getResources().getColor(R.color.opForeground));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(statusView, statusLp);

        buildSessionFab();

        cursorView = makeCursor();
        FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(
                cursorSizePx(), cursorSizePx());
        cursorView.setVisibility(View.GONE);
        root.addView(cursorView, cursorLp);

        setContentView(root);
        applyImmersive(); // re-apply now that root exists so insets are consumed

        // React to layout changes (fold/unfold, rotation, IME). Pushes new dimensions
        // to Parsec so the absolute mouse mapping & rendered resolution stay correct.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            if (surface != null && (w != or - ol || h != ob - ot)) {
                surface.onClientResize(w, h);
            }
        });

        String sessionId = getIntent().getStringExtra(LoginActivity.EXTRA_SESSION_ID);
        String peerId = getIntent().getStringExtra(HostListActivity.EXTRA_PEER_ID);
        if (sessionId == null || peerId == null) {
            statusView.setText("Missing session/peer.");
            return;
        }

        parsec = new Parsec();
        parsec.setLogCallback();
        parsec.init();
        int e = parsec.clientConnect(sessionId, peerId);
        if (e == parsec.PARSEC_OK) {
            statusView.setVisibility(View.GONE);
            surface = new ClientGLSurface(getApplicationContext());
            surface.setParsec(parsec);
            applySettingsToSurface();
            surface.setTrackpadListener((x, y, visible) -> {
                if (cursorView == null) return;
                if (!visible) { cursorView.setVisibility(View.GONE); return; }
                int size = cursorView.getWidth();
                if (size == 0) size = cursorSizePx();
                cursorView.setTranslationX(x - size / 2f);
                cursorView.setTranslationY(y - size / 2f);
                cursorView.setVisibility(View.VISIBLE);
            });
            root.addView(surface, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            surface.renderInit();
            buildMouseButtonRow();
            buildKeyboardCapture();
            buildKeyboardButton();
            buildImeAccessoryBar();
            if (DEBUG_GRID) {
                root.post(() -> {
                    debugGrid = DebugGrid.install(this, root, DEBUG_TILE_PX, DEBUG_GAP_PX);
                });
            } else if (STEALTH_GRID) {
                root.post(() -> {
                    // 4 invisible corner anchors keep the SurfaceView routed
                    // through the window compositor so overlay views render on top.
                    debugGrid = DebugGrid.installCornerAnchors(this, root);
                });
            }
        } else {
            statusView.setText("clientConnect failed (code " + e + ")");
            Toast.makeText(this, "Connect failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void buildSessionFab() {
        List<SessionFab.Item> items = new ArrayList<>();
        items.add(new SessionFab.Item("Settings", () -> openSettings()));
        // Label reflects the CURRENT mode; tapping toggles to the other mode.
        items.add(new SessionFab.Item(
                settings.isTouchpadMode() ? "Mouse: Touchpad" : "Mouse: Direct",
                () -> {
                    String next = settings.isTouchpadMode()
                            ? Settings.CURSOR_DIRECT : Settings.CURSOR_TOUCHPAD;
                    settings.cursorMode(next);
                    if (surface != null) surface.setTrackpadMode(Settings.CURSOR_TOUCHPAD.equals(next));
                    updateMouseButtonRow();
                    rebuildSessionFab();
                }));
        // Ctrl+Alt+Del — buried in the menu so it isn't fired by accident
        items.add(new SessionFab.Item("Ctrl+Alt+Del", this::sendCtrlAltDel));
        items.add(new SessionFab.Item("Disconnect", true, this::finish));

        fab = new SessionFab(this, root, items);
        fab.setVisible(!settings.noOverlay());
    }

    private void buildMouseButtonRow() {
        if (mouseButtonRow != null) return;
        mouseButtonRow = new MouseButtonRow(this, new MouseButtonRow.ButtonListener() {
            @Override public void onButton(int parsecButton, boolean pressed) {
                if (surface == null) return;
                surface.sendButtonExternal(parsecButton, pressed);
                heldButtonCount = Math.max(0, heldButtonCount + (pressed ? 1 : -1));
                surface.setExternalButtonHeld(heldButtonCount > 0);
            }
            @Override public void onScrollMode(boolean on) {
                if (surface == null) return;
                surface.setScrollMode(on);
            }
            @Override public void onRepositioned(float xPx, float yPx) {
                // User chose this spot — adopt it as the new resting position
                // and forget any IME-temporary backup.
                settings.mouseRowPosition(xPx, yPx);
                mouseRowImeBackupY = null;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(16);
        root.addView(mouseButtonRow, lp);
        // Restore previously-saved drag position once the row has been measured.
        mouseButtonRow.post(this::applyMouseRowSavedPosition);
        updateMouseButtonRow();
    }

    private void applyMouseRowSavedPosition() {
        if (mouseButtonRow == null) return;
        float sx = settings.mouseRowX();
        float sy = settings.mouseRowY();
        if (sx < 0 || sy < 0) return; // never dragged → leave at default bottom-center
        // Clamp to current viewport in case rotation / fold changed the bounds.
        float maxX = Math.max(0, root.getWidth()  - mouseButtonRow.getWidth());
        float maxY = Math.max(0, root.getHeight() - mouseButtonRow.getHeight());
        mouseButtonRow.setX(Math.min(Math.max(0, sx), maxX));
        mouseButtonRow.setY(Math.min(Math.max(0, sy), maxY));
    }

    private void updateMouseButtonRow() {
        if (mouseButtonRow == null) return;
        // Show only in touchpad mode; in direct mode the user taps the screen directly.
        mouseButtonRow.setVisibility(settings.isTouchpadMode() && !settings.noOverlay()
                ? View.VISIBLE : View.GONE);
    }

    private void rebuildSessionFab() {
        if (fab != null) fab.setVisible(false);
        buildSessionFab();
    }

    /** Tiny transparent EditText that captures soft-keyboard input and
     *  translates each typed character into a ParsecKeycode press/release. */
    private void buildKeyboardCapture() {
        if (keyboardCapture != null) return;
        keyboardCapture = new EditText(this);
        keyboardCapture.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyboardCapture.setBackground(null);
        keyboardCapture.setCursorVisible(false);
        keyboardCapture.setAlpha(0.01f);
        keyboardCapture.setText(" ");
        keyboardCapture.setSelection(1);

        keyboardCapture.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreCaptureChange) return;
                // Inserted characters
                if (count > before) {
                    for (int i = start + before; i < start + count; i++) {
                        sendCharToHost(s.charAt(i));
                    }
                } else if (count < before) {
                    int dels = before - count;
                    for (int i = 0; i < dels; i++) sendKeyToHost(KeyMap.KEY_BACKSPACE, false);
                }
            }
            @Override public void afterTextChanged(Editable s) {
                if (ignoreCaptureChange) return;
                // Reset to a single sentinel so we can keep detecting backspaces
                // even when the visible text would otherwise be empty. Skip if
                // the text already IS the sentinel (calling setText(" ") on a
                // " "-valued field still re-enters afterTextChanged via some
                // IMEs and would recurse infinitely).
                if (s.length() == 1 && s.charAt(0) == ' ') return;
                ignoreCaptureChange = true;
                try {
                    s.replace(0, s.length(), " ");
                    keyboardCapture.setSelection(1);
                } finally {
                    ignoreCaptureChange = false;
                }
            }
        });

        keyboardCapture.setOnKeyListener((v, keyCode, event) -> {
            int pk = KeyMap.fromAndroidKey(keyCode);
            if (pk == 0) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                sendKey(pk, true);
                sendKey(pk, false);
                return true;
            }
            return false;
        });

        // 8x8 px is too small for the IME to accept focus on some devices.
        // Keep it tiny but big enough to be a valid input target.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(40),
                Gravity.TOP | Gravity.START);
        lp.leftMargin = -dp(48); // offscreen so it isn't visible to the user
        root.addView(keyboardCapture, lp);
    }

    private static final int PK_LCTRL = 224;
    private static final int PK_LSHIFT = 225;
    private static final int PK_LALT = 226;
    private static final int PK_LGUI = 227;

    /** Send a key press+release surrounded by modifier press/release pairs from
     *  the accessory bar plus an optional implicit shift. One-shot modifiers
     *  are cleared via {@link ImeAccessoryBar#consumeArmedModifiers()}. */
    private void sendWithAccessoryModifiers(int parsecKey, boolean implicitShift) {
        int mods = imeBar != null ? imeBar.currentModifiers() : 0;
        boolean shift = implicitShift || (mods & ImeAccessoryBar.MOD_SHIFT) != 0;
        boolean ctrl  = (mods & ImeAccessoryBar.MOD_CTRL) != 0;
        boolean alt   = (mods & ImeAccessoryBar.MOD_ALT)  != 0;
        boolean meta  = (mods & ImeAccessoryBar.MOD_META) != 0;

        if (ctrl)  sendKey(PK_LCTRL, true);
        if (alt)   sendKey(PK_LALT,  true);
        if (meta)  sendKey(PK_LGUI,  true);
        if (shift) sendKey(PK_LSHIFT, true);
        sendKey(parsecKey, true);
        sendKey(parsecKey, false);
        if (shift) sendKey(PK_LSHIFT, false);
        if (meta)  sendKey(PK_LGUI,  false);
        if (alt)   sendKey(PK_LALT,  false);
        if (ctrl)  sendKey(PK_LCTRL, false);

        if (imeBar != null) imeBar.consumeArmedModifiers();
    }

    private void sendCharToHost(char ch) {
        if (ch == ' ') {
            // Space sentinel — used to detect deletions; don't forward.
            return;
        }
        int pk = KeyMap.fromChar(ch);
        if (pk == 0) return;
        sendWithAccessoryModifiers(pk, KeyMap.needsShift(ch));
    }

    private void sendKeyToHost(int parsecKey, boolean withShift) {
        sendWithAccessoryModifiers(parsecKey, withShift);
    }

    /** Fire the Ctrl+Alt+Del chord as a single sequence. Note: by default
     *  Windows blocks software-injected SAS; to make this work the host needs
     *  {@code host_ctrl_alt_del=1} in its Parsec config. */
    private void sendCtrlAltDel() {
        sendKey(PK_LCTRL, true);
        sendKey(PK_LALT,  true);
        sendKey(KeyMap.KEY_DELETE, true);
        sendKey(KeyMap.KEY_DELETE, false);
        sendKey(PK_LALT,  false);
        sendKey(PK_LCTRL, false);
    }

    private void sendKey(int parsecKey, boolean pressed) {
        if (parsec != null) {
            int rc = parsec.clientSendKeyboard(parsecKey, 0, pressed);
            Log.d("ParsecKey", "sendKey key=" + parsecKey + " pressed=" + pressed + " rc=" + rc);
        }
    }

    /** Show/hide the soft keyboard. Focuses the hidden capture EditText so the
     *  IME has somewhere to send characters. */
    private void toggleKeyboard() {
        if (keyboardCapture == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        boolean imeVisible = imeBar != null && imeBar.getVisibility() == View.VISIBLE;
        if (imeVisible) {
            imm.hideSoftInputFromWindow(keyboardCapture.getWindowToken(), 0);
        } else {
            keyboardCapture.setFocusable(true);
            keyboardCapture.setFocusableInTouchMode(true);
            keyboardCapture.requestFocus();
            imm.showSoftInput(keyboardCapture, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void buildKeyboardButton() {
        if (keyboardButton != null) return;
        keyboardButton = new ImageButton(this);
        keyboardButton.setImageResource(R.drawable.ic_keyboard);
        keyboardButton.setImageTintList(ColorStateList.valueOf(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnPrimaryContainer)));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorPrimaryContainer));
        keyboardButton.setBackground(bg);
        keyboardButton.setOnClickListener(v -> toggleKeyboard());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = dp(16);
        lp.rightMargin = dp(16);
        root.addView(keyboardButton, lp);
        updateKeyboardButton();
    }

    private void updateKeyboardButton() {
        if (keyboardButton == null) return;
        keyboardButton.setVisibility(
                settings.showKeyboardButton() && !settings.noOverlay()
                        ? View.VISIBLE : View.GONE);
    }

    /** Accessory bar that floats above the soft keyboard with Ctrl/Alt/⊞/Shift
     *  modifiers and Esc/Tab/arrows/Home/End/PgUp/PgDn/Ins/Del/F1–F12. */
    private void buildImeAccessoryBar() {
        if (imeBar != null) return;
        imeBar = new ImeAccessoryBar(this, new ImeAccessoryBar.Listener() {
            @Override public void onSpecialKey(int parsecKey) {
                Log.d("ParsecIME", "onSpecialKey parsecKey=" + parsecKey);
                sendWithAccessoryModifiers(parsecKey, false);
            }
            @Override public void onModifierTap(int parsecKey) {
                Log.d("ParsecIME", "onModifierTap parsecKey=" + parsecKey);
                // Single-tap of a modifier fires a momentary press+release
                // (e.g. ⊞ alone opens Start menu).
                sendKey(parsecKey, true);
                sendKey(parsecKey, false);
            }
            @Override public void onModifiersChanged(int modBitmask) { /* state only */ }
        });
        imeBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        root.addView(imeBar, lp);

        // Track the IME's bottom inset using the modern API. The lambda is
        // invoked every time the IME shows/hides so we can position the bar
        // immediately above it and trim the desktop letterbox to share the
        // remaining vertical space with the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int imeBottom = 0;
            int navBottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            Log.d("ParsecIME", "onApplyWindowInsets imeBottom=" + imeBottom
                    + " navBottom=" + navBottom);
            applyCutoutInsets(insets);
            onImeInsetChanged(imeBottom);
            return insets;
        });
        // Request the listener fire now to pick up an already-shown IME
        if (root.isAttachedToWindow()) root.requestApplyInsets();
    }

    /** Estimated height of the IME accessory bar (Ctrl/Alt/⊞/Shift + action row).
     *  Used directly instead of {@code imeBar.getHeight()} because the bar may
     *  not be laid out yet when the first IME-show inset arrives — reading 0
     *  would let the bar overlap the bottom of the desktop surface. */
    private int imeAccessoryHeightEstimatePx() {
        // 36dp pill button + 6dp top padding + 6dp bottom padding = 48dp
        return dp(48);
    }

    private void onImeInsetChanged(int imeBottomPx) {
        boolean imeVisible = imeBottomPx > 0;
        int barH = imeAccessoryHeightEstimatePx();

        // Accessory bar sits directly above the IME.
        if (imeBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) imeBar.getLayoutParams();
            lp.bottomMargin = imeBottomPx;
            lp.height = barH;
            imeBar.setLayoutParams(lp);
            imeBar.setVisibility(imeVisible ? View.VISIBLE : View.GONE);
            imeAccessoryHeightPx = barH;
        }

        // Dynamic viewport: when the keyboard is open, absorb its height into
        // the host's existing letterbox space FIRST before shrinking the
        // surface. So on a foldable showing 16:9 content on a near-square
        // screen, the keyboard slides into the bottom letterbox and the
        // host content keeps its full size.
        if (surface != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (slp != null) {
                int reservedBottom = imeVisible ? imeBottomPx + barH : 0;
                int letterboxBottom = computeBottomLetterboxPx();
                // If the IME + bar fit entirely inside the existing bottom
                // letterbox, leave the surface at full size (host content
                // unchanged). Otherwise shrink the surface so its bottom edge
                // sits exactly at the top of the accessory bar — the host
                // content is then re-fit by Parsec into the smaller surface.
                int targetBottomMargin = (reservedBottom > letterboxBottom)
                        ? reservedBottom : 0;
                if (slp.bottomMargin != targetBottomMargin) {
                    slp.bottomMargin = targetBottomMargin;
                    surface.setLayoutParams(slp);
                }
            }
        }

        // Lift the keyboard FAB above the accessory bar when the keyboard is
        // open.
        int liftBy = imeVisible ? imeBottomPx + barH + dp(8) : dp(16);
        if (keyboardButton != null) {
            FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) keyboardButton.getLayoutParams();
            klp.bottomMargin = liftBy;
            keyboardButton.setLayoutParams(klp);
        }

        // Mouse-button row: two modes.
        //  • Default (never dragged) — adjust its bottomMargin so it rides
        //    above the accessory bar when the IME opens.
        //  • User-positioned (dragged) — if the row would be covered by the
        //    IME, temporarily translate it just above the bar; restore the
        //    user's position when the IME closes.
        if (mouseButtonRow != null) {
            boolean userPositioned =
                    settings.mouseRowX() >= 0 && settings.mouseRowY() >= 0;
            if (!userPositioned) {
                FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
                mlp.bottomMargin = liftBy;
                mouseButtonRow.setLayoutParams(mlp);
            } else if (imeVisible) {
                // The IME (+ accessory bar) occludes from the top of imeBar
                // downwards. If the row's bottom edge crosses that, slide it up.
                int rowH = mouseButtonRow.getHeight();
                int rootH = root.getHeight();
                float occlusionTop = rootH - imeBottomPx - barH;
                float currentY = mouseButtonRow.getY();
                if (currentY + rowH > occlusionTop) {
                    if (mouseRowImeBackupY == null) mouseRowImeBackupY = currentY;
                    float targetY = Math.max(0, occlusionTop - rowH - dp(8));
                    mouseButtonRow.setY(targetY);
                }
            } else if (mouseRowImeBackupY != null) {
                // IME closed — restore the user's drag position.
                mouseButtonRow.setY(mouseRowImeBackupY);
                mouseRowImeBackupY = null;
            }
        }
    }

    /** Compute the current bottom letterbox space (in pixels) inside the
     *  surface, based on the host aspect and the surface dimensions. */
    private int computeBottomLetterboxPx() {
        if (root == null) return 0;
        int sw = root.getWidth();
        int sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return 0;
        int hostW = settings.hostWidth();
        int hostH = settings.hostHeight();
        if (hostW <= 0 || hostH <= 0) return 0;
        // Aspect-fit: host fills width OR height while preserving aspect.
        float hostAspect = (float) hostW / hostH;
        float screenAspect = (float) sw / sh;
        int renderedHeight;
        if (screenAspect > hostAspect) {
            // Screen wider than host → fills full height, side letterbox.
            renderedHeight = sh;
        } else {
            // Screen taller than host → fills full width, top+bottom letterbox.
            renderedHeight = Math.round(sw / hostAspect);
        }
        int totalLetterboxV = Math.max(0, sh - renderedHeight);
        // Symmetric — bottom half of the letterbox is what can absorb the IME.
        return totalLetterboxV / 2;
    }

    /** Respect display cutouts (camera punch-outs / notches) by padding the
     *  root so floating buttons stay clear of the cutout region. */
    private void applyCutoutInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        android.view.DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null || root == null) return;
        // Pad the root so children laid out with edge gravity steer clear of
        // the cutout. Use the maximum on each side observed.
        int left = cutout.getSafeInsetLeft();
        int top = cutout.getSafeInsetTop();
        int right = cutout.getSafeInsetRight();
        int bottom = cutout.getSafeInsetBottom();
        root.setPadding(left, top, right, bottom);
    }

    private View makeCursor() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x80FFFFFF);
        g.setStroke(dp(2), getResources().getColor(R.color.opAccent));
        v.setBackground(g);
        return v;
    }

    private int cursorSizePx() {
        // dp(18) baseline, scaled by user cursorScale setting (default 1.0)
        return Math.max(dp(8), Math.round(dp(18) * settings.cursorScale()));
    }

    private void applySettingsToSurface() {
        if (surface == null) return;
        surface.setTrackpadMode(settings.isTouchpadMode());
        surface.setSensitivity(settings.mouseSensitivity());
        surface.setHostDimensions(settings.hostWidth(), settings.hostHeight());
        if (cursorView != null) {
            int sz = cursorSizePx();
            ViewGroup.LayoutParams lp = cursorView.getLayoutParams();
            if (lp != null) {
                lp.width = sz; lp.height = sz;
                cursorView.setLayoutParams(lp);
            }
        }
        if (fab != null) fab.setVisible(!settings.noOverlay());
        updateMouseButtonRow();
        updateKeyboardButton();
    }

    private void openSettings() {
        if (settingsOverlay != null) return;
        settingsOverlay = SettingsPanel.build(this, settings, this::closeSettings);
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeSettings() {
        if (settingsOverlay != null) {
            root.removeView(settingsOverlay);
            settingsOverlay = null;
            applySettingsToSurface();
        }
    }

    private void applyImmersive() {
        Window window = getWindow();
        // Edge-to-edge: extend the layout under system bars (does NOT relocate
        // the window — leaves the Material3 NoActionBar theme to handle bounds).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            window.getDecorView();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (root != null) {
            root.setFitsSystemWindows(false);
            // Insets listener intentionally NOT set here: buildImeAccessoryBar
            // installs its own listener that tracks IME state. Re-applying
            // immersive on onResume must not overwrite it.
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 4-finger tap recovery: if any single gesture reaches 4 simultaneous
        // pointers, snap the FAB and mouse-button row back to their default
        // positions so the user can recover from accidentally dragging them
        // offscreen.
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureMaxPointerCount = 1;
                gestureStartMs = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pc = ev.getPointerCount();
                if (pc > gestureMaxPointerCount) gestureMaxPointerCount = pc;
                if (gestureMaxPointerCount >= 4
                        && System.currentTimeMillis() - gestureStartMs < FOUR_FINGER_TAP_WINDOW_MS) {
                    triggerOverlayReset();
                    // Reset only once per gesture
                    gestureMaxPointerCount = 0;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureMaxPointerCount = 0;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Recovery hatch invoked by a 4-finger tap. Snaps overlays back to their
     *  default positions and clears any saved drag location, so a user who
     *  has dragged the mouse row or FAB offscreen can get them back. */
    private void triggerOverlayReset() {
        if (fab != null) fab.resetPosition();
        if (mouseButtonRow != null) {
            settings.resetMouseRowPosition();
            mouseRowImeBackupY = null;
            // Clear drag translation so the LayoutParams gravity takes over again.
            mouseButtonRow.setTranslationX(0);
            mouseButtonRow.setTranslationY(0);
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
            mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mlp.bottomMargin = dp(16);
            mouseButtonRow.setLayoutParams(mlp);
        }
        Toast.makeText(this, "Controls reset", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
        // A fold/unfold can drop touch events mid-gesture and shrinks/expands
        // the viewport, which can leave overlays offscreen and host input
        // state stuck. Re-fit everything once the new layout pass has run.
        root.post(this::recoverFromConfigurationChange);
    }

    /** Run after a fold/unfold (or other config change) once the layout pass
     *  has produced new dimensions. Re-centers the desktop view, releases any
     *  phantom input state, and re-clamps overlays so the user can't lose them
     *  by folding around them. */
    private void recoverFromConfigurationChange() {
        // FIRST: clear any stale layout state (e.g. surface bottomMargin from a
        // pre-fold IME-open scenario) so the surface re-occupies the full root.
        // This must happen BEFORE the GL onSurfaceChanged callback fires, so
        // the host frame is centered against the correct viewport.
        if (surface != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (slp != null && slp.bottomMargin != 0) {
                slp.bottomMargin = 0;
                surface.setLayoutParams(slp);
            }
            surface.requestLayout();
        }
        // Release any locked/armed modifiers — the host may not have received
        // the matching release events if the gesture was interrupted by fold.
        if (imeBar != null) imeBar.clearLatchedModifiers();
        heldButtonCount = 0;
        // Re-clamp user-positioned mouse-button row so it can't drift offscreen.
        reclampMouseRow();
        // After the next layout pass, reset touch state & cursor against the
        // freshly-laid-out surface dimensions.
        root.post(() -> {
            if (surface != null) surface.resetTouchState();
        });
    }

    /** If the mouse-button row has a saved drag position, clamp it to the
     *  current viewport bounds. Folding from large→small screen can leave the
     *  saved coords entirely offscreen. */
    private void reclampMouseRow() {
        if (mouseButtonRow == null) return;
        float sx = settings.mouseRowX();
        float sy = settings.mouseRowY();
        if (sx < 0 || sy < 0) return; // never dragged
        int rw = root.getWidth();
        int rh = root.getHeight();
        int rowW = mouseButtonRow.getWidth();
        int rowH = mouseButtonRow.getHeight();
        if (rw <= 0 || rh <= 0 || rowW <= 0 || rowH <= 0) return;
        // If the row would be more than half offscreen, snap it back to the
        // default bottom-center placement and forget the saved position.
        float maxX = Math.max(0, rw - rowW);
        float maxY = Math.max(0, rh - rowH);
        boolean wayOff = sx > rw - rowW / 2f || sy > rh - rowH / 2f
                || sx + rowW / 2f < 0 || sy + rowH / 2f < 0;
        if (wayOff) {
            settings.resetMouseRowPosition();
            mouseRowImeBackupY = null;
            mouseButtonRow.setTranslationX(0);
            mouseButtonRow.setTranslationY(0);
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
            mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mlp.bottomMargin = dp(16);
            mouseButtonRow.setLayoutParams(mlp);
            return;
        }
        float cx = Math.min(Math.max(0, sx), maxX);
        float cy = Math.min(Math.max(0, sy), maxY);
        if (cx != sx || cy != sy) settings.mouseRowPosition(cx, cy);
        mouseButtonRow.setX(cx);
        mouseButtonRow.setY(cy);
    }

    @Override
    protected void onPause() {
        if (surface != null) surface.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null) surface.onResume();
        applyImmersive();
    }

    @Override
    protected void onDestroy() {
        if (surface != null) surface.shutdown();
        if (parsec != null) {
            parsec.clientDestroy();
            parsec.destroy();
            parsec = null;
        }
        super.onDestroy();
    }
}
