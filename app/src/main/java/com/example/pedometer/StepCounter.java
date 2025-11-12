package com.example.pedometer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计步器类，用于处理步数检测相关的算法
 */
public class StepCounter {

    /**
     * 对数据进行均值滤波处理
     *
     * @param data0 原始数据列表
     * @param n1 第一次滤波的窗口大小
     * @param n2 第二次滤波的窗口大小
     * @return 经过两次均值滤波处理后的数据列表
     */
    // 移植自Filter_Mean 函数
    public static List<Double> filterMean(List<Double> data0, int n1, int n2) {
        // 第一次均值滤波
        List<Double> data1 = new ArrayList<>();
        for (int i = 0; i <= data0.size() - n1; i++) {
            double sum = 0;
            for (int j = 0; j < n1; j++) {
                sum += data0.get(i + j);
            }
            data1.add(sum / n1);
        }

        // 第二次均值滤波
        List<Double> data2 = new ArrayList<>();
        for (int i = 0; i <= data1.size() - n2; i++) {
            double sum = 0;
            for (int j = 0; j < n2; j++) {
                sum += data1.get(i + j);
            }
            data2.add(sum / n2);
        }
        return data2;
    }

    /**
     * 检测步数
     *
     * @param data 经过滤波处理的数据
     * @return 检测到的步数
     */
    public static int numStepsDetection(List<Double> data) {
        if (data == null || data.isEmpty()) {
            return 0;
        }

        int numSteps = 0;
        double peakThreshold = 0.8; // 峰值阈值，用于过滤噪声
        double timeThreshold = 0.2; // 时间阈值，用于避免在短时间内重复计数（秒）

        int lastStepTime = 0;

        for (int i = 1; i < data.size() - 1; i++) {
            double previous = data.get(i - 1);
            double current = data.get(i);
            double next = data.get(i + 1);

            // 检测峰值
            if (current > previous && current > next && current > peakThreshold) {
                // 检查距离上一次计步的时间间隔
                if ((i - lastStepTime) * (1.0 / 50.0) > timeThreshold) { // 假设采样频率为50Hz
                    numSteps++;
                    lastStepTime = i;
                }
            }
        }

        return numSteps;
    }
}