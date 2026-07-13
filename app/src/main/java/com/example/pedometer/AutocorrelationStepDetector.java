package com.example.pedometer;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于自相关检测的计步算法
 * 通过分析加速度信号的周期性特征来识别步数
 * 相较于波峰检测算法，对噪声和信号形变具有更强的鲁棒性
 */
public class AutocorrelationStepDetector {
    private static final String TAG = "AutoCorrelationStepDetector";

    private KalmanFilter kalmanFilter;
    private List<Integer> stepIndices;

    // 复用数组，减少内存分配
    private double[] filteredBuffer;
    private double[] autocorrBuffer;

    // 参数配置
    private int windowSize;          // 滑动窗口大小
    private double minPeriod;        // 最小步态周期（点数）
    private double maxPeriod;        // 最大步态周期（点数）
    private double autocorrThreshold; // 自相关阈值

    public AutocorrelationStepDetector() {
        this.kalmanFilter = new KalmanFilter();
        this.stepIndices = new ArrayList<>();

        // 默认参数
        this.windowSize = 100;
        this.minPeriod = 15;  // 约0.3秒 @ 50Hz
        this.maxPeriod = 50;  // 约1.0秒 @ 50Hz
        this.autocorrThreshold = 0.5;

        // 预分配缓冲区
        this.filteredBuffer = new double[200];
        this.autocorrBuffer = new double[100];
    }

    /**
     * 根据手机位置调整参数
     */
    public void adjustParametersForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                // 口袋：信号弱，放宽阈值
                this.autocorrThreshold = 0.35;
                this.minPeriod = 12;
                this.maxPeriod = 45;
                break;
            case CHEST:
                // 胸前：中等信号
                this.autocorrThreshold = 0.45;
                this.minPeriod = 15;
                this.maxPeriod = 50;
                break;
            case HAND_SWING:
                // 手持摆动：强信号，严格阈值
                this.autocorrThreshold = 0.55;
                this.minPeriod = 18;
                this.maxPeriod = 55;
                break;
            case HAND_HELD:
                // 手持静止：高阈值
                this.autocorrThreshold = 0.60;
                this.minPeriod = 20;
                this.maxPeriod = 55;
                break;
            case BAG:
                // 包里：中等偏弱
                this.autocorrThreshold = 0.40;
                this.minPeriod = 15;
                this.maxPeriod = 48;
                break;
            default:
                // 未知：默认参数
                this.autocorrThreshold = 0.50;
                this.minPeriod = 15;
                this.maxPeriod = 50;
                break;
        }
    }

    /**
     * 计步核心方法
     * @param accelerationData 加速度数据窗口
     * @return 检测到的步数
     */
    public int detectSteps(double[] accelerationData) {
        if (accelerationData == null || accelerationData.length < 20) {
            return 0;
        }

        int dataLen = accelerationData.length;

        // 确保缓冲区足够大
        if (filteredBuffer.length < dataLen) {
            filteredBuffer = new double[dataLen];
        }
        if (autocorrBuffer.length < dataLen) {
            autocorrBuffer = new double[dataLen];
        }

        stepIndices.clear();

        // 阶段1: 卡尔曼滤波降噪
        double[] filteredData = kalmanFilter.filterToBuffer(accelerationData, filteredBuffer);

        // 阶段2: 计算自相关函数
        double[] autocorrValues = calculateAutocorrelation(filteredData);

        // 阶段3: 检测自相关峰值
        detectAutocorrelationPeaks(autocorrValues);

        // 阶段4: 去除误检峰值
        removeFalsePeaks(autocorrValues);

        return stepIndices.size();
    }

    /**
     * 计算归一化自相关函数
     * 公式(2-5): x(m,t) = Σ[(a(m+k) - μ(m,t)) × (a(m+k+t) - μ(m+t,t))] / (t × σ(m,t) × σ(m+t,t))
     */
    private double[] calculateAutocorrelation(double[] data) {
        int n = data.length;
        double[] autocorr = new double[n];

        // 计算全局均值
        double globalMean = 0;
        for (double v : data) {
            globalMean += v;
        }
        globalMean /= n;

        // 计算全局标准差
        double globalStdDev = 0;
        for (double v : data) {
            globalStdDev += (v - globalMean) * (v - globalMean);
        }
        globalStdDev = Math.sqrt(globalStdDev / n);

        if (globalStdDev < 1e-10) {
            // 信号几乎没有变化，返回全0
            return autocorr;
        }

        // 计算不同延迟t的自相关值
        // 只计算合理的步态周期范围内的自相关
        int minLag = (int) minPeriod;
        int maxLag = Math.min((int) maxPeriod, n / 2);

        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            int count = 0;

            // 计算分子：协方差
            for (int m = 0; m < n - lag; m++) {
                sum += (data[m] - globalMean) * (data[m + lag] - globalMean);
                count++;
            }

            // 归一化：除以标准差的平方（方差）和样本数
            double normalizedCorr = sum / (count * globalStdDev * globalStdDev);

            // 存储自相关值
            if (lag < n) {
                autocorr[lag] = normalizedCorr;
            }
        }

        return autocorr;
    }

    /**
     * 检测自相关函数中的峰值
     * 自相关函数的峰值对应步态周期
     */
    private void detectAutocorrelationPeaks(double[] autocorr) {
        int n = autocorr.length;

        // 只在合理范围内查找峰值
        int minLag = (int) minPeriod;
        int maxLag = Math.min((int) maxPeriod, n / 2);

        // 查找自相关峰值
        for (int i = minLag + 2; i < maxLag - 2; i++) {
            // 必须超过阈值
            if (autocorr[i] < autocorrThreshold) {
                continue;
            }

            // 检查是否为局部峰值
            boolean isPeak = true;
            int searchWindow = 3;  // 在±3个点范围内检查

            for (int j = 1; j <= searchWindow; j++) {
                if (autocorr[i] <= autocorr[i - j] || autocorr[i] <= autocorr[i + j]) {
                    isPeak = false;
                    break;
                }
            }

            if (isPeak) {
                // 记录峰值位置（对应步态周期的延迟量）
                stepIndices.add(i);
            }
        }
    }

    /**
     * 去除误检峰值
     * 根据峰值间距和强度过滤不合理的峰值
     */
    private void removeFalsePeaks(double[] autocorr) {
        if (stepIndices.size() < 2) {
            return;
        }

        // 计算相邻峰值间距
        int[] distances = new int[stepIndices.size() - 1];
        for (int i = 0; i < stepIndices.size() - 1; i++) {
            distances[i] = stepIndices.get(i + 1) - stepIndices.get(i);
        }

        // 计算中位数
        int medianDist = findMedian(distances);

        // 如果中位数不合理，直接清空
        if (medianDist < minPeriod || medianDist > maxPeriod) {
            stepIndices.clear();
            return;
        }

        // 去除距离过近的峰值
        List<Integer> toRemove = new ArrayList<>();
        double threshold = medianDist * 0.6;

        for (int i = 0; i < distances.length; i++) {
            if (distances[i] < threshold) {
                // 保留自相关值更大的峰值
                double peak1 = autocorr[stepIndices.get(i)];
                double peak2 = autocorr[stepIndices.get(i + 1)];

                if (peak1 > peak2) {
                    toRemove.add(stepIndices.get(i + 1));
                } else {
                    toRemove.add(stepIndices.get(i));
                }
            }
        }

        // 从后往前移除
        for (int i = stepIndices.size() - 1; i >= 0; i--) {
            int index = stepIndices.get(i);
            for (int removeIdx : toRemove) {
                if (removeIdx == index) {
                    stepIndices.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * 查找数组的中位数
     */
    private int findMedian(int[] array) {
        if (array.length == 0) return 0;

        int[] sorted = array.clone();
        java.util.Arrays.sort(sorted);

        int mid = sorted.length / 2;
        if (sorted.length % 2 == 0) {
            return (sorted[mid - 1] + sorted[mid]) / 2;
        } else {
            return sorted[mid];
        }
    }

    /**
     * 根据检测到的周期数估算步数
     * 每个周期代表一步
     */
    public int estimateStepsFromPeriods(int dataLength, int samplingRate) {
        if (stepIndices.isEmpty()) {
            return 0;
        }

        // 计算平均周期
        double totalPeriod = 0;
        for (int period : stepIndices) {
            totalPeriod += period;
        }
        double avgPeriod = totalPeriod / stepIndices.size();

        // 估算步数 = 总时间 / 平均周期
        double totalTime = dataLength / (double) samplingRate;
        double estimatedSteps = totalTime / (avgPeriod / samplingRate);

        return Math.max(0, (int) Math.round(estimatedSteps));
    }

    /**
     * 获取检测到的峰值索引
     */
    public List<Integer> getStepIndices() {
        return new ArrayList<>(stepIndices);
    }

    /**
     * 重置检测器
     */
    public void reset() {
        stepIndices.clear();
        kalmanFilter.reset();
    }
}