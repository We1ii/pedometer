package com.example.pedometer;

import android.util.Log;

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
     * 检测步数（基于何成文的峰值检测算法）
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

        // 01-计算均值，用于设置阈值
        double sum = 0;
        for (double value : data) {
            sum += value;
        }
        double thres = sum / data.size();
        if (thres < 0.5) {
            thres = 0.5;
        }
        Log.d(TAG, "阈值 Thres = " + String.format("%.3f", thres));

        // 02-计算两组向上零点和向下零点的下标
        int biaozhiU = 0;  // 向上零点标志
        int biaozhiL = 0;  // 向下零点标志

        for (int i = 1; i < data.size() - 1; i++) {
            if (biaozhiU == 0) {
                // 向上零点检测条件
                if (data.get(i - 1) < 0 && data.get(i) > 0) {
                    biaozhiU = i;
                }
            } else if (biaozhiL == 0) {
                // 向下零点检测条件
                if (data.get(i) > 0 && data.get(i + 1) < 0) {
                    biaozhiL = i;
                }
            }

            if (biaozhiU > 0 && biaozhiL > 0) {
                break;
            }
        }

        // 得到下标差值，得到峰值判断的条件数目
        int N = (int) Math.ceil(Math.abs(biaozhiU - biaozhiL) / 2.0);
        if (N == 0) {
            N = 1; // 防止N为0
        }
        Log.d(TAG, "零点检测: 向上=" + biaozhiU + ", 向下=" + biaozhiL + ", N=" + N);

        // 03-检测峰峰值
        for (int i = N; i < data.size() - N; i++) {
            boolean biaozhi = false;
            if (data.get(i) > thres) {
                for (int j = 1; j <= N; j++) {
                    if (data.get(i) < data.get(i - j) || data.get(i) < data.get(i + j)) {
                        biaozhi = true;
                        break;
                    }
                }
                if (!biaozhi) {
                    index.add(i);
                }
            }
        }

        // 04-当存在多峰现象，需要去除
        int initialPeakCount = index.size();
        Log.d(TAG, "初始检测到峰值: " + initialPeakCount + " 个");
        
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
        int wuyon2 = sortedDiff.get(sortedDiff.size() / 2);

        // 设置阈值
        double wuyon3 = wuyon2 / 2.0;
        Log.d(TAG, "多峰去除: 中位数差值=" + wuyon2 + ", 阈值=" + String.format("%.1f", wuyon3));

        // 存储疑似多峰的标记
        List<Integer> biaoji = new ArrayList<>();
        for (int i = 0; i < wuyon1.size(); i++) {
            if (wuyon1.get(i) < wuyon3) {
                // 当差分序列某个值小于阈值，即两个峰值之间挨得太近
                // 从两个挨得太近的峰值中干掉一个
                if (data.get(index.get(i)) > data.get(index.get(i + 1))) {
                    biaoji.add(index.get(i + 1));
                } else {
                    biaoji.add(index.get(i));
                }
            }
        }

        // 把标记变量存储的对应数据下标干掉
        if (!biaoji.isEmpty()) {
            for (int j = biaoji.size() - 1; j >= 0; j--) {
                for (int k = index.size() - 1; k >= 0; k--) {
                    if (biaoji.get(j).equals(index.get(k))) {
                        index.remove(k);
                    }
                }
            }
            Log.d(TAG, "去除多峰 " + biaoji.size() + " 个，剩余峰值 " + index.size() + " 个");
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