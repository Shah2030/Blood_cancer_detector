package com.example.bloodcancerdetector;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import com.example.bloodcancerdetector.MainActivity;
import com.example.bloodcancerdetector.R;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.splash_screen);

        // Delay 2 seconds before going to MainActivity
        new Handler().postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, 2000);
    }
}
