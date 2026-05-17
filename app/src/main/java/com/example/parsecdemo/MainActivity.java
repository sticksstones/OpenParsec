package com.example.parsecdemo;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If a previous login is still persisted, skip the login screen and
        // go directly to the host list. HostListActivity will bounce back
        // here via LoginActivity if the saved token has expired.
        String saved = new Settings(this).sessionId();
        if (saved != null && !saved.isEmpty()) {
            Intent i = new Intent(this, HostListActivity.class);
            i.putExtra(LoginActivity.EXTRA_SESSION_ID, saved);
            startActivity(i);
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
        finish();
    }
}
