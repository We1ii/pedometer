package com.example.pedometer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类，负责处理计步器的用户界面和启动/停止计步服务
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStepCount;
    private Button btnSelectSensors;
    private boolean isPedometerRunning = false;

    private int totalSteps = 0;

    private BroadcastReceiver stepUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("pedometer-step-update")) {
                totalSteps = intent.getIntExtra("totalSteps", 0);
                tvStepCount.setText(String.valueOf(totalSteps));
            }
        }
    };

    private final ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    allGranted &= Boolean.TRUE.equals(granted);
                }
                if (allGranted) {
                    startPedometerService();
                } else {
                    // 权限未授予，提示用户相关功能不可用
                }
            }
    );

    /**
     * Activity创建时的回调方法，初始化界面和相关组件
     * 
     * @param savedInstanceState 保存的实例状态，用于恢复Activity状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        tvStepCount = findViewById(R.id.tv_step_count);
        btnSelectSensors = findViewById(R.id.btn_select_sensors);

        // 恢复保存的状态
        if (savedInstanceState != null) {
            isPedometerRunning = savedInstanceState.getBoolean("isPedometerRunning");
            totalSteps = savedInstanceState.getInt("totalSteps");
            tvStepCount.setText(String.valueOf(totalSteps));
            if (isPedometerRunning) {
                btnSelectSensors.setText("停止计步");
                btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_red_400));
            } else {
                btnSelectSensors.setText("开始计步");
                btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_blue_500));
            }
        }

        // 设置按钮点击事件监听器
        btnSelectSensors.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isPedometerRunning) {
                    stopPedometer();
                } else {
                    startPedometer();
                }
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(stepUpdateReceiver, new IntentFilter("pedometer-step-update"), RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(stepUpdateReceiver);
    }

    /**
     * 启动计步器功能
     */
    private void startPedometer() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (permissionsToRequest.isEmpty()) {
            ensureBatteryOptimizationExemption();
            startPedometerService();
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        }
    }

    private void ensureBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void startPedometerService() {
        isPedometerRunning = true;
        totalSteps = 0;
        tvStepCount.setText("0");
        btnSelectSensors.setText("停止计步");
        btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_red_400));

        Intent serviceIntent = new Intent(this, PedometerService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    /**
     * 停止计步器功能
     */
    private void stopPedometer() {
        isPedometerRunning = false;
        btnSelectSensors.setText("开始计步");
        btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_blue_500));

        Intent serviceIntent = new Intent(this, PedometerService.class);
        stopService(serviceIntent);
    }

    /**
     * 保存Activity状态
     * 
     * @param outState 用于保存状态的Bundle对象
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isPedometerRunning", isPedometerRunning);
        outState.putInt("totalSteps", totalSteps);
    }
}