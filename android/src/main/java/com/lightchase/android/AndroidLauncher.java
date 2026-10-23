package com.lightchase.android;
import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.lightchase.client.LightChaseApp;
import com.lightchase.client.FakeGateway;

public final class AndroidLauncher extends AndroidApplication {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        initialize(new LightChaseApp(new FakeGateway()), config);
    }
}
