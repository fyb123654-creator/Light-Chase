package com.lightchase.android;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        initialize(new LightChaseApp(new FakeGateway(), WebSocketClientGateway::new, new LanPermissionImpl()),
                config);
    }
}
