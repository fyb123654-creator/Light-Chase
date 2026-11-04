package com.lightchase.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.lightchase.client.FakeGateway;
import com.lightchase.client.LanPermission;
import com.lightchase.client.LightChaseApp;
import com.lightchase.network.WebSocketClientGateway;

/**
 * Android entry point.
 *
 * <p>Injects two gateways: the offline fixture (default, works with no server) and a factory for
 * the real WebSocket gateway. Also implements the platform gate for {@code ACCESS_LOCAL_NETWORK},
 * which Android 17 / targetSdk 37 requires before a LAN socket may be opened.
 */
public final class AndroidLauncher extends AndroidApplication {

    /** ACCESS_LOCAL_NETWORK, spelled out so the constant is not required at compile time. */
    private static final String PERMISSION_LAN = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int REQUEST_LAN = 1001;

    private final class LanPermissionImpl implements LanPermission {
        @Override public boolean granted() {
            // The permission only exists from API 37; below that no local-network gate applies.
            if (Build.VERSION.SDK_INT < 37) return true;
            return checkSelfPermission(PERMISSION_LAN) == PackageManager.PERMISSION_GRANTED;
        }

        @Override public void request() {
            if (Build.VERSION.SDK_INT < 37) return;
            runOnUiThread(() -> requestPermissions(new String[]{PERMISSION_LAN}, REQUEST_LAN));
        }

        @Override public String describe() {
            if (Build.VERSION.SDK_INT < 37) return "NOT REQUIRED (API < 37)";
            return granted() ? "GRANTED" : "NOT GRANTED";
        }
    }

    /**
     * Hides the keyboard and takes the focus away from libGDX's hidden EditText, so a later tap
     * cannot bring the keyboard back on its own.
     */
    private void hideKeyboard() {
        // Called from the GL thread; touching views must happen on the UI thread.
        runOnUiThread(() -> {
            View root = getWindow().getDecorView();
            clearEditableFocus(root);
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
                // Hiding alone leaves the IME served by libGDX's hidden EditText, and the next
                // touch makes Android show it again. Restarting the input session and moving the
                // focus to the GL surface drops that target for good.
                imm.restartInput(root);
            }
            root.clearFocus();
            View content = findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) content;
                if (group.getChildCount() > 0) {
                    View surface = group.getChildAt(0);
                    surface.setFocusableInTouchMode(true);
                    surface.requestFocus();
                }
            }
        });
    }

    private static void clearEditableFocus(View view) {
        if (view instanceof EditText) {
            view.clearFocus();
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearEditableFocus(group.getChildAt(i));
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LightChaseApp.hideKeyboardHook = this::hideKeyboard;
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        initialize(new LightChaseApp(new FakeGateway(), WebSocketClientGateway::new, new LanPermissionImpl()),
                config);
    }
}
