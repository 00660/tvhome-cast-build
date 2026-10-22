package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/** Lets the user change HOME through the system UI, without shell or privileged permissions. */
public class HomeSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            SelfDrawnUi.Toast.show(this, getString(R.string.home_settings_help));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            SelfDrawnUi.Toast.show(this, getString(R.string.not_available));
        } finally {
            finish();
        }
    }
}
