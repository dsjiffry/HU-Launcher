package com.hu.launcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity that asks Android for screen-capture consent, then hands it over. */
public class CaptureActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), 1);
            // With the helper on, accept Android 9's "Start now" prompt so the PC needs no touch here.
            if (AutoClicker.available()) AutoClicker.clickWhenSeen(6000, AutoClicker.SYSTEMUI, "Start now");
        } catch (RuntimeException e) {
            Toast.makeText(this, "Screen capture isn't available on this unit", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (result == RESULT_OK && data != null) {
            ScreenCapture.start(getApplicationContext(), result, data);
            Toast.makeText(this, "Screen shared with your PC", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
