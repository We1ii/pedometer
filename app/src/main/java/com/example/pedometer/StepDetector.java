package com.example.pedometer;

import java.util.ArrayList;
import java.util.List;

//计步算法核心（峰值检测)
public class StepDetector {
    private static final String TAG = "StepDetector";

    private KalmanFilter kalmanFilter;
    private List<Integer> stepIndices;
    private double thres;
    private int N;

    public StepDetector() {
        this.kalmanFilter = new KalmanFilter();
        this.stepIndices = new ArrayList<>();
    }

//    public int detectSteps(double[] accelerationData) {
//        if (accelerationData == null || accelerationData.length < 10) {
//            return 0;
//        }
//
//        //卡尔曼滤波降噪
//        double[] filteredData = kalmanFilter.filter(accelerationData);
//
//        //峰值增强
//        double[] enhancedData = enhancePeaks(filteredData);
//
//        stepIndices.clear();
//
//        // 计算阈值 (归一化处理，让峰值更明显)
//        //  初始阈值设置为0.5
//        thres = calculateThreshold(enhancedData);
//        if (thres < 0.3) {
//            thres = 0.3;
//        }
//
//        //查找过零点 找到第一个从负到正的过零点和从正到负的过零点
//        int[] zeroCrossings = findZeroCrossings(enhancedData);
//        if (zeroCrossings[0] == -1 || zeroCrossings[1] == -1) {
//            return 0;
//        }
//
//        N = (int) Math.ceil(Math.abs(zeroCrossings[0] - zeroCrossings[1]) / 2.0);
//        if (N < 1) {
//            N = 1;
//        }
//
//        //检测峰值
//        detectPeaks(enhancedData);
//
//        //去除误检峰值
//        removeFalsePeaks(enhancedData);
//
//        return stepIndices.size();
//    }

    //计步算法核心流程 (StepDetector.detectSteps)
    //输入: double[100] accelerationData
    //输出: int 检测到的步数
    public int detectSteps(double[] accelerationData) {
        if (accelerationData == null || accelerationData.length < 10) {
            return 0;
        }

        //在正式处理前先计算信号强度（RMS值）
        //如果强度 < 0.15，直接返回0步，避免微弱噪声被误检
        double signalStrength = calculateSignalStrength(accelerationData);
        if (signalStrength < 0.15) {
            return 0;
        }

        //阶段1: 卡尔曼滤波降噪
        double[] filteredData = kalmanFilter.filter(accelerationData);

        //阶段2: 峰值增强
        double[] enhancedData = enhancePeaks(filteredData);

        stepIndices.clear();

        //阶段3: 计算阈值 (使用动态阈值，基于标准差)
        thres = calculateThreshold(enhancedData);
        if (thres < 0.65) {
            thres = 0.65;
        }

        //阶段4: 查找过零点确定窗口大小N 找到第一个从负到正的过零点和从正到负的过零点
        int[] zeroCrossings = findZeroCrossings(enhancedData);
        if (zeroCrossings[0] == -1 || zeroCrossings[1] == -1) {
            return 0;
        }

        N = (int) Math.ceil(Math.abs(zeroCrossings[0] - zeroCrossings[1]) / 2.0);
        if (N < 1) {
            N = 1;
        }
        if (N > 5) {
            N = 5;
        }

        // 阶段5: 检测峰值
        detectPeaks(enhancedData);

        // 阶段6: 去除误检峰值
        removeFalsePeaks(enhancedData);

        return stepIndices.size();
    }

    private double calculateSignalStrength(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v * v;
        }
        return Math.sqrt(sum / data.length);
    }

//    private double[] enhancePeaks(double[] data) {
//        double[] enhanced = new double[data.length];
//        double maxAbs = 0;
//
//        for (double v : data) {
//            maxAbs = Math.max(maxAbs, Math.abs(v));
//        }
//        maxAbs = maxAbs + 1e-10;
//
//        for (int i = 0; i < data.length; i++) {
//            enhanced[i] = data[i] / maxAbs * maxAbs;
//        }
//
//        return enhanced;
//    }

    private double[] enhancePeaks(double[] data) {
        double[] enhanced = new double[data.length];

        // 使用立方根增强峰值，比平方更温和，减少小波动的放大
        for (int i = 0; i < data.length; i++) {
            double absVal = Math.abs(data[i]);
            //非线性增强 越大的数被放得越大，小信号变化不大 同时保留原来的正负方向。
            enhanced[i] = Math.pow(absVal, 1.5) * Math.signum(data[i]);
        }

        // 平滑处理，减少噪声
        if (data.length > 3) {
            double[] smoothed = new double[data.length];
            //保留首尾两个数据
            smoothed[0] = enhanced[0];
            smoothed[data.length - 1] = enhanced[data.length - 1];

            for (int i = 1; i < data.length - 1; i++) {
                //3点加权平滑
                smoothed[i] = (enhanced[i - 1] + enhanced[i] * 2 + enhanced[i + 1]) / 4.0;
            }

            return smoothed;
        }

        return enhanced;
    }

    //计算动态阈值
    private double calculateThreshold(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v;
        }
//        return sum / data.length;

        double mean = sum / data.length;

        // 计算标准差
        double varianceSum = 0;
        for (double v : data) {
            varianceSum += (v - mean) * (v - mean);
        }
        double stdDev = Math.sqrt(varianceSum / data.length);

        // 使用均值 + 0.5倍标准差作为阈值，更适应不同强度的步态
        return mean + 0.5 * stdDev;
    }

    private int[] findZeroCrossings(double[] data) {
        int[] result = {-1, -1};
        int biaozhiU = 0;
        int biaozhiL = 0;

        for (int i = 1; i < data.length - 1; i++) {
            if (biaozhiU == 0) {
                if (data[i - 1] < 0 && data[i] > 0) {
                    biaozhiU = i;
                }
            } else if (biaozhiL == 0) {
                if (data[i] > 0 && data[i + 1] < 0) {
                    biaozhiL = i;
                }
            }

            if (biaozhiU > 0 && biaozhiL > 0) {
                break;
            }
        }

        result[0] = biaozhiU;
        result[1] = biaozhiL;
        return result;
    }

    private void detectPeaks(double[] data) {
        for (int i = N; i < data.length - N; i++) {
            if (data[i] > thres) {
                //初步必须满足大于阈值
                boolean isPeak = true;

                for (int j = 1; j <= N; j++) {
                    //检查左右各N个点（N区间内）是否都小于data[i]
                    if (data[i] < data[i - j] || data[i] < data[i + 1]) {
                        isPeak = false;
                        break;
                    }
                }

                if (isPeak) {
                    //极大值峰值索引下标
                    stepIndices.add(i);
                }
            }
        }
    }

    private void removeFalsePeaks(double[] data) {
        if (stepIndices.size() < 2) {
            return;
        }

        //① 计算相邻峰值间距数组(各个峰值之间的距离值 N个峰值 N-1个距离值)
        int[] distances = new int[stepIndices.size() - 1];
        for (int i = 0; i < stepIndices.size() - 1; i++) {
            distances[i] = stepIndices.get(i + 1) - stepIndices.get(i);
        }
        //② 计算中位数
        int medianDist = findMedian(distances);
        //③ 提高阈值系数从0.4到0.35，更严格地去除近距离峰值
        double threshold = medianDist * 0.35;

        //误检的峰值索引下标（需要剔除的）
        List<Integer> toRemove = new ArrayList<>();

        //④ 如果峰值间的间距 < 阈值 (如果两个"峰值"距离太近（比如只有3-5个点），说明其中一个是误检)
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] < threshold) {
                // 保留峰值更高的那个
                if (data[stepIndices.get(i)] > data[stepIndices.get(i + 1)]) {
                    toRemove.add(stepIndices.get(i + 1));
                } else {
                    toRemove.add(stepIndices.get(i));
                }
            }
        }

        for (int i = stepIndices.size() - 1; i >= 0; i--) {
            int index = stepIndices.get(i);
            for (int removeIdx : toRemove) {
                if (removeIdx == index) {
                    //remove执行时 会向前移动位置 所以从后往前操作
                    stepIndices.remove(i);
                    break;
                }
            }
        }
    }

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

    public List<Integer> getStepIndices() {
        return new ArrayList<>(stepIndices);
    }

    public void reset() {
        stepIndices.clear();
        kalmanFilter.reset();
    }
}
