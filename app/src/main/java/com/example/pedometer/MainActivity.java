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
import android.widget.Toast;
import android.util.Log;
import android.content.ClipboardManager;
import android.content.ClipData;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.File;

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

    private static final String TAG = "MainActivity";
    private TextView tvStepCount;
    private Button btnSelectSensors;
    private Button btnOpenFolder;
    private boolean isPedometerRunning = false;

    private int totalSteps = 0;

    private BroadcastReceiver stepUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "收到广播: " + intent.getAction());
            if (intent.getAction().equals("pedometer-step-update")) {
                totalSteps = intent.getIntExtra("totalSteps", 0);
                tvStepCount.setText(String.valueOf(totalSteps));
            } else if (intent.getAction().equals("pedometer-export-complete")) {
                String filePath = intent.getStringExtra("filePath");
                Log.i(TAG, "收到导出完成广播，文件路径: " + filePath);
                if (filePath != null) {
                    Toast.makeText(MainActivity.this, "数据导出成功: " + new File(filePath).getName(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "数据导出失败", Toast.LENGTH_LONG).show();
                }
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
        btnOpenFolder = findViewById(R.id.btn_open_folder);

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
        
        // 设置打开文件夹按钮点击事件
        btnOpenFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDataFolder();
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
        IntentFilter filter = new IntentFilter();
        filter.addAction("pedometer-step-update");
        filter.addAction("pedometer-export-complete");
        registerReceiver(stepUpdateReceiver, filter, RECEIVER_NOT_EXPORTED);
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
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
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
        try {
            Log.i(TAG, "开始启动计步服务");
            
            isPedometerRunning = true;
            totalSteps = 0;
            tvStepCount.setText("0");
            btnSelectSensors.setText("停止计步");
            btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_red_400));

            Intent serviceIntent = new Intent(this, PedometerService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            
            Log.i(TAG, "计步服务启动命令已发送");
        } catch (Exception e) {
            Log.e(TAG, "启动计步服务失败", e);
            Toast.makeText(this, "启动计步服务失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            
            // 恢复UI状态
            isPedometerRunning = false;
            btnSelectSensors.setText("开始计步");
            btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_blue_500));
        }
    }

    /**
     * 停止计步器功能
     */
    private void stopPedometer() {
        Log.i(TAG, "停止计步器");
        isPedometerRunning = false;
        btnSelectSensors.setText("开始计步");
        btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_blue_500));

        Intent serviceIntent = new Intent(this, PedometerService.class);
        boolean stopped = stopService(serviceIntent);
        Log.i(TAG, "服务停止结果: " + stopped);
        
        // 显示停止提示
        Toast.makeText(this, "计步已停止，正在导出数据...", Toast.LENGTH_SHORT).show();
    }

    /**
     * 打开数据文件夹（使用Dialog展示文件列表）
     */
    private void openDataFolder() {
        File dataDir = DataExporter.getDataDirectory(this);
        if (dataDir == null || !dataDir.exists()) {
            Toast.makeText(this, "数据文件夹不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        
        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".xls") || name.endsWith(".xlsx"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "暂无数据文件", Toast.LENGTH_SHORT).show();
            return;
        }
        
        showFileListDialog(dataDir, files);
    }
    
    private void showFileListDialog(File dataDir, File[] files) {
        // 按修改时间排序（最新的在前）
        java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        // 构建文件名列表
        String[] fileNames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            long sizeKB = file.length() / 1024;
            String sizeStr = sizeKB < 1024 ? sizeKB + " KB" : String.format("%.1f MB", sizeKB / 1024.0);
            
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            String dateStr = sdf.format(new java.util.Date(file.lastModified()));
            
            fileNames[i] = file.getName() + "\n" + sizeStr + " | " + dateStr;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("数据文件 (共 " + files.length + " 个)")
            .setItems(fileNames, (dialog, which) -> {
                // 点击文件，尝试打开
                openFile(files[which]);
            })
            .setPositiveButton("复制路径", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("文件路径", dataDir.getAbsolutePath());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("关闭", null)
            .show();
    }
    
    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            intent.setDataAndType(uri, "application/vnd.ms-excel");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "没有找到可以打开Excel文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "打开文件失败", e);
            Toast.makeText(this, "打开文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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