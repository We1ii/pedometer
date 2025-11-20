package com.example.pedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 计步器服务类，用于在后台持续监测并计算用户的步数
 * 该服务使用加速度传感器数据来检测步数，并在通知栏显示当前步数统计
 */
public class PedometerService extends Service implements SensorEventListener {

    private static final String TAG = "PedometerService";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "PedometerChannel";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private List<Double> accData = new ArrayList<>();
    private List<DataExporter.AccelerometerData> rawAccData = new ArrayList<>();
    private int totalSteps = 0;
    private long sessionStartTime = 0;

    private Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private long lastProcessTime = 0;
    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            if (lastProcessTime > 0) {
                long interval = currentTime - lastProcessTime;
                Log.i(TAG, "processStepData 触发，距上次间隔: " + interval + "ms");
            }
            lastProcessTime = currentTime;
            
            processStepData();
            handler.postDelayed(this, 3000);
        }
    };

    /**
     * 服务创建时的回调方法
     * 初始化传感器管理器和加速度传感器
     */
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Log.i(TAG, "PedometerService onCreate 开始");
            
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensorManager == null) {
                Log.e(TAG, "无法获取传感器管理器");
                return;
            }
            
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                Log.e(TAG, "设备不支持加速度传感器");
                return;
            }
            Log.i(TAG, "加速度传感器初始化成功");
            
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pedometer::PedometerService");
                wakeLock.setReferenceCounted(false);
                Log.i(TAG, "唤醒锁初始化成功");
            }
            
            createNotificationChannel();
            Log.i(TAG, "PedometerService onCreate 完成");
        } catch (Exception e) {
            Log.e(TAG, "PedometerService onCreate 失败", e);
        }
    }

    /**
     * 服务启动时的回调方法
     * 启动前台服务，注册传感器监听器并开始处理步数数据
     *
     * @param intent 启动服务的意图
     * @param flags 附加信息标志
     * @param startId 标识服务启动次数的ID
     * @return 服务重新启动模式
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Log.i(TAG, "PedometerService onStartCommand 开始");
            
            sessionStartTime = System.currentTimeMillis();
            totalSteps = 0;
            accData.clear();
            rawAccData.clear();
            
            Log.i(TAG, "准备启动前台服务");
            startForeground(NOTIFICATION_ID, createNotification());
            
            // 检查传感器是否可用
            if (accelerometer == null) {
                Log.e(TAG, "加速度传感器不可用");
                return START_NOT_STICKY;
            }
            
            Log.i(TAG, "注册传感器监听器");
            boolean registered = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (!registered) {
                Log.e(TAG, "传感器注册失败");
                return START_NOT_STICKY;
            }
            
            Log.i(TAG, "启动定时任务");
            handler.post(runnable);
            
            Log.i(TAG, "获取唤醒锁");
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
            
            Log.i(TAG, "PedometerService 启动成功");
            return START_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "PedometerService 启动失败", e);
            return START_NOT_STICKY;
        }
    }

    /**
     * 服务销毁时的回调方法
     * 取消注册传感器监听器并移除定时任务
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        Log.i(TAG, "PedometerService onDestroy 开始");
        Log.i(TAG, "原始加速度数据: " + rawAccData.size() + " 条");
        Log.i(TAG, "处理后的G值数据: " + accData.size() + " 条");
        
        // 先停止定时任务和传感器，防止在最后处理时继续收集数据
        Log.i(TAG, "停止定时任务和传感器监听");
        handler.removeCallbacks(runnable);
        sensorManager.unregisterListener(this);
        
        // 停止前最后一次计算步数，确保与MATLAB算法结果一致
        // 这一步非常关键：在最后3秒或不满3秒时点击停止，必须再次处理所有累积的数据
        Log.i(TAG, "执行最后一次步数计算（处理所有累积数据）");
        int stepsBeforeFinal = totalSteps;
        processStepData();
        Log.i(TAG, "最后一次计算完成，步数从 " + stepsBeforeFinal + " 更新到 " + totalSteps);
        
        // 导出数据（使用最终计算的步数）
        exportSessionData();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "释放唤醒锁");
        }
        
        Log.i(TAG, "PedometerService onDestroy 完成，最终步数: " + totalSteps);
    }

    /**
     * 绑定服务时的回调方法
     *
     * @param intent 绑定服务的意图
     * @return 用于与服务通信的IBinder对象，此处返回null表示不支持绑定
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 传感器数值变化时的回调方法
     * 处理加速度传感器数据，计算重力去除后的加速度幅值并存储
     *
     * @param event 包含传感器数据的事件对象
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            long timestamp = System.currentTimeMillis();

            // 存储原始加速度数据
            rawAccData.add(new DataExporter.AccelerometerData(x, y, z, timestamp));

            // 每收集50条数据打印一次日志
            if (rawAccData.size() % 50 == 0) {
                Log.i(TAG, "已收集加速度数据: " + rawAccData.size() + " 条");
            }

            // 计算加速度幅值并减去重力加速度得到实际运动加速度
            double magnitude = Math.sqrt(x * x + y * y + z * z) - 9.81;
            accData.add(magnitude);
        }
    }

    /**
     * 传感器精度变化时的回调方法
     *
     * @param sensor 发生精度变化的传感器
     * @param accuracy 当前精度
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing
    }

    /**
     * 处理步数数据
     * 每次对所有累积数据重新计算总步数（与MATLAB算法完全一致）
     */
    private void processStepData() {
        if (accData.isEmpty()) {
            Log.i(TAG, "processStepData: 没有数据需要处理");
            return;
        }

        int currentDataSize = accData.size();
        Log.i(TAG, "processStepData: 当前累积数据 " + currentDataSize + " 条");

        // 需要至少16个数据点才能进行滤波（10+6）
        if (currentDataSize < 20) {
            Log.i(TAG, "processStepData: 数据量不足，等待更多数据");
            return;
        }

        // 使用所有累积的数据进行处理（与MATLAB相同）
        List<Double> dataToProcess = new ArrayList<>(accData);

        // 对数据进行均值滤波处理
        List<Double> filteredData = StepCounter.filterMean(dataToProcess, 10, 6);
        Log.i(TAG, "processStepData: 原始数据 " + dataToProcess.size() + " 条，滤波后 " + filteredData.size() + " 条");
        
        // 检测步数（每次重新计算总步数）
        int previousSteps = totalSteps;
        totalSteps = StepCounter.numStepsDetection(filteredData);

        Log.i(TAG, "========================================");
        Log.i(TAG, "processStepData: 原始数据 " + dataToProcess.size() + " 条");
        Log.i(TAG, "processStepData: 滤波后 " + filteredData.size() + " 条");
        Log.i(TAG, "processStepData: 步数 " + previousSteps + " -> " + totalSteps);
        Log.i(TAG, "========================================");

        updateNotification(totalSteps);
        broadcastStepUpdate(totalSteps);
    }

    /**
     * 创建通知渠道（仅适用于Android O及以上版本）
     */
    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.i(TAG, "创建通知渠道");
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Pedometer Service Channel",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("计步器服务通知");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                    Log.i(TAG, "通知渠道创建成功");
                } else {
                    Log.e(TAG, "无法获取通知管理器");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "创建通知渠道失败", e);
        }
    }

    /**
     * 创建服务前台通知
     *
     * @return 构建好的通知对象
     */
    private Notification createNotification() {
        try {
            Log.i(TAG, "创建前台通知");
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("计步器正在运行")
                    .setContentText("步数: " + totalSteps)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOngoing(true)
                    .build();
            
            Log.i(TAG, "前台通知创建成功");
            return notification;
        } catch (Exception e) {
            Log.e(TAG, "创建前台通知失败", e);
            // 返回一个最简单的通知
            return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("计步器")
                    .setContentText("正在运行")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
        }
    }

    /**
     * 更新通知中的步数显示
     *
     * @param steps 当前总步数
     */
    private void updateNotification(int steps) {
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("计步器正在运行")
                .setContentText("当前步数: " + steps)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 向界面广播最新步数数据
     *
     * @param steps 当前总步数
     */
    private void broadcastStepUpdate(int steps) {
        Intent intent = new Intent("pedometer-step-update");
        intent.putExtra("totalSteps", steps);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
    
    /**
     * 导出会话数据到Excel文件
     */
    private void exportSessionData() {
        Log.i(TAG, "开始导出会话数据，原始数据条数: " + rawAccData.size());
        
        if (rawAccData.isEmpty()) {
            Log.w(TAG, "没有数据需要导出");
            return;
        }
        
        try {
            long endTime = System.currentTimeMillis();
            int samplingFrequency = 50; // 假设采样频率为50Hz
            
            Log.i(TAG, "会话时长: " + (endTime - sessionStartTime) + "ms, 总步数: " + totalSteps);
            
            String filePath = DataExporter.exportToExcel(
                this, 
                rawAccData, 
                sessionStartTime, 
                endTime, 
                samplingFrequency, 
                totalSteps
            );
            
            if (filePath != null) {
                Log.i(TAG, "数据导出成功: " + filePath);
                // 广播导出完成事件
                Intent intent = new Intent("pedometer-export-complete");
                intent.putExtra("filePath", filePath);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            } else {
                Log.e(TAG, "数据导出失败");
            }
        } catch (Exception e) {
            Log.e(TAG, "导出数据时发生异常", e);
        }
    }
}
