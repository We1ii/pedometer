package com.example.pedometer;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类，负责处理计步器的用户界面和传感器数据采集
 * 实现SensorEventListener接口以监听传感器事件
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView tvStepCount;
    private Button btnSelectSensors;
    private boolean isPedometerRunning = false;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private List<Double> accData = new ArrayList<>();
    private int totalSteps = 0;

    /**
     * 定时任务，定期处理传感器数据并更新步数
     */
    private Handler handler = new Handler();
    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (isPedometerRunning) {
                processStepData();
                handler.postDelayed(this, 3000);
            }
        }
    };

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

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // 恢复保存的状态
        if (savedInstanceState != null) {
            isPedometerRunning = savedInstanceState.getBoolean("isPedometerRunning");
            totalSteps = savedInstanceState.getInt("totalSteps");
            tvStepCount.setText(String.valueOf(totalSteps));
            if (isPedometerRunning) {
                btnSelectSensors.setText("停止计步");
                btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_red_400));
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
                handler.post(runnable);
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

    /**
     * 启动计步器功能
     */
    private void startPedometer() {
        isPedometerRunning = true;
        btnSelectSensors.setText("停止计步");
        btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_red_400));
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        handler.post(runnable);
    }

    /**
     * 停止计步器功能
     */
    private void stopPedometer() {
        isPedometerRunning = false;
        btnSelectSensors.setText("开始计步");
        btnSelectSensors.setBackgroundColor(ContextCompat.getColor(this, R.color.modern_blue_500));
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(runnable);
    }

    /**
     * 处理传感器数据并计算步数
     */
    private void processStepData() {
        if (accData.isEmpty()) {
            return;
        }

        // 复制当前数据并清空原始列表
        List<Double> dataToProcess = new ArrayList<>(accData);
        accData.clear();

        // 对数据进行滤波处理并检测步数
        List<Double> filteredData = StepCounter.filterMean(dataToProcess, 10, 6);
        int steps = StepCounter.numStepsDetection(filteredData);
        totalSteps += steps;
        tvStepCount.setText(String.valueOf(totalSteps));
    }

    /**
     * 传感器数据变化时的回调方法
     * 
     * @param event 传感器事件，包含传感器数据
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // 计算加速度的模值并减去重力加速度
            double magnitude = Math.sqrt(x * x + y * y + z * z) - 9.81;
            accData.add(magnitude);
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

    /**
     * 传感器精度变化时的回调方法
     * 
     * @param sensor 传感器对象
     * @param accuracy 新的精度值
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing
    }
}