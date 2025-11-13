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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 计步器服务类，用于在后台持续监测并计算用户的步数
 * 该服务使用加速度传感器数据来检测步数，并在通知栏显示当前步数统计
 */
public class PedometerService extends Service implements SensorEventListener {

    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "PedometerChannel";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private List<Double> accData = new ArrayList<>();
    private int totalSteps = 0;

    private Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
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
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pedometer::PedometerService");
            wakeLock.setReferenceCounted(false);
        }
        createNotificationChannel();
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
        startForeground(NOTIFICATION_ID, createNotification());
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        handler.post(runnable);
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        return START_STICKY;
    }

    /**
     * 服务销毁时的回调方法
     * 取消注册传感器监听器并移除定时任务
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(runnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
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
     * 对收集到的加速度数据进行滤波和步数检测，并更新总步数和通知
     */
    private void processStepData() {
        if (accData.isEmpty()) {
            return;
        }

        List<Double> dataToProcess = new ArrayList<>(accData);
        accData.clear();

        // 对数据进行均值滤波处理
        List<Double> filteredData = StepCounter.filterMean(dataToProcess, 10, 6);
        // 检测步数
        int steps = StepCounter.numStepsDetection(filteredData);
        totalSteps += steps;

        updateNotification(totalSteps);
        broadcastStepUpdate(totalSteps);
    }

    /**
     * 创建通知渠道（仅适用于Android O及以上版本）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Pedometer Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 创建服务前台通知
     *
     * @return 构建好的通知对象
     */
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("计步器正在运行")
                .setContentText("步数: " + totalSteps)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
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
}
