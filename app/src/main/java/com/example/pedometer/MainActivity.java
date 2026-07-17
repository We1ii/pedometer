//public class MainActivity extends AppCompatActivity {
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        EdgeToEdge.enable(this);
//        setContentView(R.layout.activity_main);
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
//            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
//            return insets;
//        });
//    }
//}

package com.example.pedometer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

//主界面和传感器数据收集
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometerSensor;

    //解决息屏时数据丢失问题
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private StepDetector stepDetector;
    private TextView stepCountText;
    private TextView statusText;
    private Button btnStartStop;
    private Button btnOpenFolder;
    private Button btnExportData;

    private double[] accelerationBuffer;
    private int bufferIndex;
    private static final int BUFFER_SIZE_DEFAULT = 100;
    private static final int OVERLAP_SIZE_DEFAULT = 30;
    private int currentBufferSize;
    private int currentOverlapSize;
    private int totalSteps;
    private boolean isCounting = false;

    private long lastStepTime = 0;
    private static final long MIN_STEP_INTERVAL = 250;
    private double[] recentAccelerations;
    private int recentAccIndex = 0;
    private static final int RECENT_ACC_SIZE = 40;

    //三轴数据导出
    private List<DataExporter.AccelerometerData> rawDataList;
    private long sessionStartTime;
    private long sessionEndTime;
    private static final int SAMPLING_FREQUENCY = 50;

    // 当前检测到的姿态（用于导出）
    private String currentPositionStr = "未知";

    // 屏幕状态广播接收器
    private BroadcastReceiver screenReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initSensors();
        initStepDetector();
        setupButtons();
        initScreenReceiver();  // 初始化屏幕状态监听
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 处理配置变更（如屏幕旋转），但不重启Activity
        // 由于设置了android:configChanges，这里不会被调用重建
        // 计步状态会保持不变
    }

    // 初始化屏幕状态广播接收器
    private void initScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    // 屏幕点亮
                    if (isCounting) {
                        // 重新注册传感器
                        sensorManager.registerListener(MainActivity.this,
                                accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);

                        // 重新获取WakeLock
                        if (wakeLock != null && !wakeLock.isHeld()) {
                            wakeLock.acquire(10 * 60 * 1000L);
                        }
                    }
                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    // 屏幕熄灭 - 不做任何操作，保持传感器运行
                    // WakeLock会保证CPU继续运行
                }
            }
        };
    }

    //初始化界面
    private void initViews() {
        stepCountText = findViewById(R.id.tv_step_count);
        statusText = findViewById(R.id.tv_status);
        btnStartStop = findViewById(R.id.btn_select_sensors);
        btnOpenFolder = findViewById(R.id.btn_open_folder);
        btnExportData = findViewById(R.id.btn_export_data);
    }

    //初始化传感器
    private void initSensors() {
        //获取 SensorManager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        //检查传感器可用性
        if (sensorManager != null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometerSensor == null) {
                statusText.setText("错误：设备不支持加速度传感器");
                btnStartStop.setEnabled(false);
            } else {
                statusText.setText("传感器就绪，点击「开始计步」开始记录");
            }
        } else {
            statusText.setText("错误：无法获取传感器服务");
            btnStartStop.setEnabled(false);
        }
    }

    //初始化计步器
    private void initStepDetector() {
        stepDetector = new StepDetector();
        //初始化动态窗口大小
        currentBufferSize = BUFFER_SIZE_DEFAULT;
        currentOverlapSize = OVERLAP_SIZE_DEFAULT;
        //创建滑动窗口（使用最大可能的大小）
        accelerationBuffer = new double[150];  // 最大窗口=150（口袋模式）
        bufferIndex = 0;
        //步数计数器
        totalSteps = 0;
        //静止检测窗口
        recentAccelerations = new double[RECENT_ACC_SIZE];
        java.util.Arrays.fill(recentAccelerations, 0);

        rawDataList = new ArrayList<>();
        sessionStartTime = 0;
        sessionEndTime = 0;
    }

    //设置按钮监听
    private void setupButtons() {
        btnStartStop.setOnClickListener(v -> {
            if (!isCounting) {
                startStepCounting();
            } else {
                stopStepCounting();
            }
        });

        //Toast提示(待实现)
        btnOpenFolder.setOnClickListener(v -> {
            Toast.makeText(this, "打开文件夹功能待实现", Toast.LENGTH_SHORT).show();
        });

        btnExportData.setOnClickListener(v -> exportSensorData());
    }

    //用户点击"开始计步"
    private void startStepCounting() {
        if (accelerometerSensor == null) {
            Toast.makeText(this, "传感器不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        isCounting = true;

        if (powerManager == null) {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }

        if (wakeLock == null && powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Pedometer::StepCounterLock"
            );
            wakeLock.setReferenceCounted(false);
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }

        //注册传感器监听
        sensorManager.registerListener(this, accelerometerSensor,
                SensorManager.SENSOR_DELAY_GAME);

        // 注册屏幕状态广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        //更新UI: 按钮变红，显示"正在计步中..."
        btnStartStop.setText("停止计步");
        btnStartStop.setBackgroundTintList(getColorStateList(android.R.color.holo_red_light));
        statusText.setText("正在计步中...");

        //处理初始静止时 出现步数情况
        totalSteps = 0;
        bufferIndex = 0;
        //重置动态窗口大小为默认值
        currentBufferSize = BUFFER_SIZE_DEFAULT;
        currentOverlapSize = OVERLAP_SIZE_DEFAULT;
        //清空 accelerationBuffer[]
        //清空 recentAccelerations[]
        java.util.Arrays.fill(accelerationBuffer, 0);
        java.util.Arrays.fill(recentAccelerations, 0);
        recentAccIndex = 0;
        lastStepTime = 0;
        stepDetector.reset();

        rawDataList.clear();
        sessionStartTime = System.currentTimeMillis();
        sessionEndTime = 0;

        Toast.makeText(this, "开始计步", Toast.LENGTH_SHORT).show();
    }

    private void stopStepCounting() {
        isCounting = false;

        // 注销传感器监听
        sensorManager.unregisterListener(this);

        // 注销屏幕状态广播接收器
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException e) {
                // 接收器未注册，忽略
            }
        }

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        btnStartStop.setText("开始计步");
        btnStartStop.setBackgroundTintList(getColorStateList(R.color.app_primary_blue));
        statusText.setText(String.format(Locale.getDefault(),
                "已停止，总计：%d 步", totalSteps));

        Toast.makeText(this, "停止计步，总计：" + totalSteps + " 步", Toast.LENGTH_SHORT).show();
    }

    //传感器数据采集与处理流程
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isCounting || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        //步骤1: 计算合加速度并去除重力
        float[] values = event.values;

        // 创建数据点时带上当前姿态
        DataExporter.AccelerometerData dataPoint = new DataExporter.AccelerometerData(
                values[0], values[1], values[2], System.currentTimeMillis(),
                currentPositionStr
        );
        rawDataList.add(dataPoint);

        double magnitude = Math.sqrt(
                values[0] * values[0] +
                        values[1] * values[1] +
                        values[2] * values[2]
        );

        double gravityRemoved = magnitude - 9.81;

        //步骤2: 更新静止检测窗口
        recentAccelerations[recentAccIndex] = gravityRemoved;
        recentAccIndex = (recentAccIndex + 1) % RECENT_ACC_SIZE;

        //步骤3: 判断是否静止 (isStationary())
        if (isStationary()) {
            // 静止时更新姿态
            currentPositionStr = "手持静止";
            //步骤4: 填充滑动窗口缓冲区
            accelerationBuffer[bufferIndex] = gravityRemoved;
            bufferIndex++;

            if (bufferIndex >= currentBufferSize) {
                // 静止模式：使用当前窗口大小复制重叠区域
                System.arraycopy(accelerationBuffer, currentBufferSize - currentOverlapSize,
                        accelerationBuffer, 0, currentOverlapSize);
                bufferIndex = currentOverlapSize;
            }
            return;
        }

        accelerationBuffer[bufferIndex] = gravityRemoved;
        bufferIndex++;

        //并且需要当缓冲区满时 (bufferIndex >= currentBufferSize)，触发计步算法
        if (bufferIndex >= currentBufferSize) {
            //优化后 检测出来的步数（每一轮检测出来的步数）
            int newSteps = stepDetector.detectSteps(accelerationBuffer, currentOverlapSize);

            // 更新当前姿态（用于导出）
            currentPositionStr = PositionDetector.getPositionDescription(
                    stepDetector.getCurrentPosition());

            if (newSteps > 0) {
                totalSteps += newSteps;
                lastStepTime = System.currentTimeMillis();

                // 保存当前总步数用于UI更新
                final int currentTotalSteps = totalSteps;
                final int detectedSteps = Math.min(newSteps, 6);
                final String positionStr = currentPositionStr;

                runOnUiThread(() -> {
                    stepCountText.setText(String.format(Locale.getDefault(),
                            "%d", currentTotalSteps));
                    statusText.setText(String.format(Locale.getDefault(),
                            "[%s] 正在计步... 总计：%d 步（本次检测：%d 步）",
                            positionStr, currentTotalSteps, detectedSteps));
                });

            } else {
                final int currentTotalSteps = totalSteps;

                //无新步数时的UI更新  即使没有检测到新步数，也更新状态提示
                runOnUiThread(() -> {
                    statusText.setText(String.format(Locale.getDefault(),
                            "[%s] 正在计步... 总计：%d 步", currentPositionStr, currentTotalSteps));
                });
            }

            //先保存当前使用的窗口大小（用于arraycopy）
            int oldBufferSize = currentBufferSize;
            int oldOverlapSize = currentOverlapSize;

            //根据本次检测到的位置，更新下次的窗口大小
            PositionDetector.PhonePosition detectedPosition = stepDetector.getCurrentPosition();
            currentBufferSize = getBufferSizeForPosition(detectedPosition);
            currentOverlapSize = getOverlapSizeForBuffer(currentBufferSize);

            //执行 arraycopy：使用旧的窗口大小来复制重叠区域
            //  源位置: oldBufferSize - oldOverlapSize
            //  目标位置: 0
            //  复制长度: oldOverlapSize（使用旧的重叠大小）
            System.arraycopy(accelerationBuffer, oldBufferSize - oldOverlapSize,
                    accelerationBuffer, 0, oldOverlapSize);
            bufferIndex = oldOverlapSize;
        }
    }

    private void openDataFolder() {
        File dataDir = DataExporter.getDataDirectory(this);
        if (dataDir != null && dataDir.exists()) {
            try {
                Uri uri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        dataDir
                );

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "resource/folder");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                startActivity(Intent.createChooser(intent, "打开文件夹"));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开文件夹: " + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        } else {
            Toast.makeText(this, "数据文件夹不存在", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportSensorData() {
        if (rawDataList == null || rawDataList.isEmpty()) {
            Toast.makeText(this, "没有可导出的数据", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在导出数据...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String filePath = DataExporter.exportToExcel(
                    this,
                    rawDataList,
                    sessionStartTime > 0 ? sessionStartTime : System.currentTimeMillis(),
                    sessionEndTime > 0 ? sessionEndTime : System.currentTimeMillis(),
                    SAMPLING_FREQUENCY,
                    totalSteps
            );

            runOnUiThread(() -> {
                if (filePath != null) {
                    Toast.makeText(this, "导出成功！\n" + filePath, Toast.LENGTH_LONG).show();

                    try {
                        File file = new File(filePath);
                        Uri uri = FileProvider.getUriForFile(
                                this,
                                getPackageName() + ".fileprovider",
                                file
                        );

                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, "application/vnd.ms-excel");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        startActivity(Intent.createChooser(intent, "打开文件"));
                    } catch (Exception e) {
                        Toast.makeText(this, "文件已保存，但无法自动打开", Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    }
                } else {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

   // 计算方差，如果方差 < 0.05，认为手机静止 返回true 静止时只收集数据但不进行步数检测
    private boolean isStationary() {
        double sum = 0;
        for (double acc : recentAccelerations) {
            sum += acc;
        }
        double mean = sum / RECENT_ACC_SIZE;

        double varianceSum = 0;
        for (double acc : recentAccelerations) {
            varianceSum += (acc - mean) * (acc - mean);
        }
        double variance = varianceSum / RECENT_ACC_SIZE;

        return variance < 0.05;
    }

    /**
     * 根据手机位置获取滑动窗口大小
     * 口袋/包里信号弱，需要更大窗口来捕捉完整步态周期
     */
    private int getBufferSizeForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                return 150;  // 口袋：更大窗口，捕捉弱信号周期
            case BAG:
                return 130;  // 包里：稍大窗口
            default:
                return BUFFER_SIZE_DEFAULT;  // 其他：保持默认
        }
    }

    /**
     * 根据窗口大小计算重叠区域（保持约20%重叠）
     */
    private int getOverlapSizeForBuffer(int bufferSize) {
        return Math.max(OVERLAP_SIZE_DEFAULT, bufferSize / 5);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 不在这里自动注册传感器，由用户点击按钮控制
        // 如果正在计步，重新注册传感器监听（息屏后亮屏时恢复）
        if (isCounting && accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor,
                    SensorManager.SENSOR_DELAY_GAME);

            // 重新获取WakeLock（如果被释放了）
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 如果正在计步，暂停传感器
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 注销传感器
        if (isCounting) {
            sensorManager.unregisterListener(this);
        }

        // 注销广播接收器
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException e) {
                // 接收器未注册，忽略
            }
        }

        // 重置计步器
        if (stepDetector != null) {
            stepDetector.reset();
        }

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}