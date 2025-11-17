package com.example.pedometer;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计步器类，用于处理步数检测的算法
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
        String TAG = "StepCounter";
        if (data0 == null || data0.size() < n1 + n2) {
            return new ArrayList<>();
        }
        
        // 输出原始数据的前几个值
        StringBuilder sb = new StringBuilder("滤波前(前10个): ");
        for (int i = 0; i < Math.min(10, data0.size()); i++) {
            sb.append(String.format("%.2f", data0.get(i)));
            if (i < Math.min(10, data0.size()) - 1) sb.append(", ");
        }
        Log.d(TAG, sb.toString());

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
        
        // 输出滤波后数据的前几个值
        StringBuilder sb2 = new StringBuilder("滤波后(前10个): ");
        for (int i = 0; i < Math.min(10, data2.size()); i++) {
            sb2.append(String.format("%.2f", data2.get(i)));
            if (i < Math.min(10, data2.size()) - 1) sb2.append(", ");
        }
        Log.d(TAG, sb2.toString());
        Log.d(TAG, "滤波: " + data0.size() + " -> " + data1.size() + " -> " + data2.size());
        
        return data2;
    }

    /**
     * 检测步数（基于峰值检测算法）
     * 核心思想：
     * 1. 检测向上零点和向下零点的下标
     * 2. 两个下标做差，得到差值
     * 3. N=差值/2（向上取整）
     * 4. 峰值检测，大于峰值点左边和右边的N个点即可
     * 5. 去除多峰现象
     *
     * @param data 经过滤波处理的数据
     * @return 检测到的峰值下标列表
     */
    public static List<Integer> numStepsDetectionCwhe2(List<Double> data) {
        List<Integer> index = new ArrayList<>();
        String TAG = "StepCounter";

        if (data == null || data.size() < 3) {
            Log.w(TAG, "数据量不足: " + (data == null ? "null" : data.size()));
            return index;
        }

        // 01-计算均值和标准差，用于设置阈值
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double value : data) {
            sum += value;
            if (value < min) min = value;
            if (value > max) max = value;
        }
        double mean = sum / data.size();
        
        // 计算标准差
        double variance = 0;
        for (double value : data) {
            variance += Math.pow(value - mean, 2);
        }
        double stdDev = Math.sqrt(variance / data.size());
        
        // 使用均值作为阈值（与MATLAB一致）
        double thres = mean;
        if (thres < 0.5) {
            thres = 0.5;
        }
        
        Log.d(TAG, "数据统计: 长度=" + data.size() + ", 均值=" + String.format("%.3f", mean) + 
                   ", 标准差=" + String.format("%.3f", stdDev) +
                   ", 最小=" + String.format("%.3f", min) + ", 最大=" + String.format("%.3f", max));
        Log.d(TAG, "阈值 Thres = " + String.format("%.3f", thres));

        // 02-计算两组向上零点和向下零点的下标（寻找前几对取平均）
        List<Integer> upZeros = new ArrayList<>();
        List<Integer> downZeros = new ArrayList<>();
        
        boolean lookingForUp = true;
        for (int i = 1; i < data.size() - 1; i++) {
            if (lookingForUp) {
                // 向上零点检测条件
                if (data.get(i - 1) < 0 && data.get(i) > 0) {
                    upZeros.add(i);
                    lookingForUp = false;
                    if (upZeros.size() >= 3) break; // 找到3对就够了
                }
            } else {
                // 向下零点检测条件
                if (data.get(i) > 0 && data.get(i + 1) < 0) {
                    downZeros.add(i);
                    lookingForUp = true;
                }
            }
        }

        // 得到下标差值，得到峰值判断的条件数目
        int N;
        if (upZeros.isEmpty() || downZeros.isEmpty()) {
            // 如果没有找到完整的零点对，使用默认值
            N = Math.max(5, data.size() / 50); // 根据数据长度估算
            Log.w(TAG, "零点检测失败，使用默认N=" + N);
        } else {
            // 使用第一对零点计算N（与MATLAB一致）
            int biaozhiU = upZeros.get(0);
            int biaozhiL = downZeros.get(0);
            N = (int) Math.ceil(Math.abs(biaozhiU - biaozhiL) / 2.0);
            if (N == 0) {
                N = 1; // 防止N为0
            }
            Log.d(TAG, "零点检测: 向上=" + biaozhiU + ", 向下=" + biaozhiL + ", N=" + N + 
                       " (找到" + upZeros.size() + "个向上零点, " + downZeros.size() + "个向下零点)");
        }

        // 03-检测峰峰值
        Log.d(TAG, "峰值检测范围: [" + N + ", " + (data.size() - N) + "), 总长度=" + data.size());
        
        int aboveThresholdCount = 0;  // 超过阈值的点数
        int candidateCount = 0;       // 候选峰值数（超过阈值且是局部最大值）
        int nearBoundaryMissed = 0;   // 边界附近错过的峰值
        
        // 检查前N个和后N个位置是否有潜在峰值被忽略
        for (int i = 0; i < N; i++) {
            if (data.get(i) > thres) nearBoundaryMissed++;
        }
        for (int i = data.size() - N; i < data.size(); i++) {
            if (data.get(i) > thres) nearBoundaryMissed++;
        }
        if (nearBoundaryMissed > 0) {
            Log.d(TAG, "边界区域有 " + nearBoundaryMissed + " 个点超过阈值（未检测）");
        }
        
        for (int i = N; i < data.size() - N; i++) {
            boolean biaozhi = false;
            if (data.get(i) > thres) {
                aboveThresholdCount++;
                for (int j = 1; j <= N; j++) {
                    if (data.get(i) < data.get(i - j) || data.get(i) < data.get(i + j)) {
                        biaozhi = true;
                        break;
                    }
                }
                if (biaozhi) {
                    continue;  // 不是峰值，跳过
                } else {
                    index.add(i);  // 是峰值，记录
                    candidateCount++;
                }
            }
        }
        
        Log.d(TAG, "峰值统计: 超过阈值=" + aboveThresholdCount + "个点, 候选峰值=" + candidateCount + "个");

        // 04-当存在多峰现象，需要去除
        int initialPeakCount = index.size();
        Log.d(TAG, "峰值检测: 初始 " + initialPeakCount + " 个");
        
        if (index.size() <= 1) {
            return index;
        }

        // 做差分运算
        List<Integer> wuyon1 = new ArrayList<>();
        for (int i = 0; i < index.size() - 1; i++) {
            wuyon1.add(index.get(i + 1) - index.get(i));
        }

        // 寻找中间值（中位数）
        List<Integer> sortedDiff = new ArrayList<>(wuyon1);
        Collections.sort(sortedDiff);
        int wuyon2;
        int size = sortedDiff.size();
        if (size % 2 == 1) {
            // 奇数个元素，取中间值
            wuyon2 = sortedDiff.get(size / 2);
        } else {
            // 偶数个元素，取中间两个值的平均值（向下取整）
            wuyon2 = (sortedDiff.get(size / 2 - 1) + sortedDiff.get(size / 2)) / 2;
        }

        // 设置阈值
        double wuyon3 = wuyon2 / 2.0;
        Log.d(TAG, "多峰去除: 中位数差值=" + wuyon2 + ", 阈值=" + String.format("%.1f", wuyon3));

        // 存储疑似多峰的标记
        List<Integer> biaoji = new ArrayList<>();
        int closeCount = 0;
        for (int i = 0; i < wuyon1.size(); i++) {
            if (wuyon1.get(i) < wuyon3) {
                closeCount++;
                // 当差分序列某个值小于阈值，即两个峰值之间挨得太近
                // 从两个挨得太近的峰值中干掉一个
                if (data.get(index.get(i)) > data.get(index.get(i + 1))) {
                    biaoji.add(index.get(i + 1));
                } else {
                    biaoji.add(index.get(i));
                }
            }
        }
        if (closeCount > 0) {
            Log.d(TAG, "发现 " + closeCount + " 对靠近的峰值，标记删除 " + biaoji.size() + " 个");
        }

        // 把标记变量存储的对应数据下标干掉
        if (!biaoji.isEmpty()) {
            // 使用Set去重，避免重复删除
            Set<Integer> toRemoveSet = new HashSet<>(biaoji);
            // 从后往前删除，避免索引变化
            List<Integer> sortedToRemove = new ArrayList<>(toRemoveSet);
            Collections.sort(sortedToRemove, Collections.reverseOrder());
            
            for (Integer valueToRemove : sortedToRemove) {
                index.remove(valueToRemove);
            }
            Log.d(TAG, "多峰去除: " + toRemoveSet.size() + " 个，最终 " + index.size() + " 个峰值");
        }
        
        // 输出前10个和后10个峰值位置用于调试
        if (index.size() > 0) {
            StringBuilder sb = new StringBuilder("峰值位置(前10个): ");
            for (int i = 0; i < Math.min(10, index.size()); i++) {
                sb.append(index.get(i));
                if (i < Math.min(10, index.size()) - 1) sb.append(", ");
            }
            Log.d(TAG, sb.toString());
            
            if (index.size() > 10) {
                StringBuilder sb2 = new StringBuilder("峰值位置(后10个): ");
                int start = Math.max(0, index.size() - 10);
                for (int i = start; i < index.size(); i++) {
                    sb2.append(index.get(i));
                    if (i < index.size() - 1) sb2.append(", ");
                }
                Log.d(TAG, sb2.toString());
            }
        }

        return index;
    }

    /**
     * 检测步数（简化接口，返回步数）
     *
     * @param data 经过滤波处理的数据
     * @return 检测到的步数
     */
    public static int numStepsDetection(List<Double> data) {
        List<Integer> peakIndices = numStepsDetectionCwhe2(data);
        return peakIndices.size();
    }
}