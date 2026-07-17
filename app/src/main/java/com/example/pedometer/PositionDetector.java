package com.example.pedometer;

/**
 * 手机位置识别器
 * 根据加速度传感器数据特征识别手机当前所在位置
 */
public class PositionDetector {

    public enum PhonePosition {
        UNKNOWN,        // 未知
        HAND_HELD,      // 手持静止
        HAND_SWING,     // 手持摆动（手臂自然下垂摆动）
        CHEST,          // 胸前/上衣口袋
        PANTS_POCKET,   // 裤子口袋
        BAG             // 包里
    }

    private static final String TAG = "PositionDetector";

    /**
     * 识别手机位置
     * @param accelerationData 加速度数据窗口
     * @return 识别出的位置
     */
    public static PhonePosition detectPosition(double[] accelerationData) {
        if (accelerationData == null || accelerationData.length < 20) {
            return PhonePosition.UNKNOWN;
        }

        // 提取特征
        double signalStrength = calculateSignalStrength(accelerationData);
        double variance = calculateVariance(accelerationData);
        double peakFrequency = estimatePeakFrequency(accelerationData);
        double dcComponent = calculateDCComponent(accelerationData);

        // 根据特征组合判断位置
        return classifyPosition(signalStrength, variance, peakFrequency, dcComponent, 0.0);
    }

    /**
     * 计算信号强度（RMS）
     */
    private static double calculateSignalStrength(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v * v;
        }
        return Math.sqrt(sum / data.length);
    }

    /**
     * 计算方差（波动程度）
     */
    private static double calculateVariance(double[] data) {
        double mean = 0;
        for (double v : data) {
            mean += v;
        }
        mean /= data.length;

        double varianceSum = 0;
        for (double v : data) {
            varianceSum += (v - mean) * (v - mean);
        }
        return varianceSum / data.length;
    }

    /**
     * 估算峰值频率（粗略估计步频）
     * 注意：这个方法返回的是周期数，不是频率
     */
    private static double estimatePeakFrequency(double[] data) {
        int zeroCrossings = 0;
        for (int i = 1; i < data.length; i++) {
            if ((data[i - 1] >= 0 && data[i] < 0) ||
                    (data[i - 1] < 0 && data[i] >= 0)) {
                zeroCrossings++;
            }
        }
        // 过零点次数的一半大约是周期数
        return zeroCrossings / 2.0;
    }

    /**
     * 计算直流分量（平均值，反映重力影响）
     */
    private static double calculateDCComponent(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v;
        }
        return Math.abs(sum / data.length);
    }

    /**
     * 根据特征分类位置
     */
    private static PhonePosition classifyPosition(double signalStrength,
                                                  double variance,
                                                  double peakFrequency,
                                                  double dcComponent,
                                                  double regularity) {

        // 规则1: 极低方差 = 静止状态（手持静止或放在桌上）
        if (variance < 0.15) {
            return PhonePosition.HAND_HELD;
        }

        // 规则2: 胸前 - 中等方差（走路时身体上下晃动），信号中等
        if (signalStrength >= 0.2 && signalStrength <= 1.5 &&
                variance >= 0.15 && variance <= 2.0) {
            return PhonePosition.CHEST;
        }

        // 规则3: 裤子口袋/包里 - 行走时的共同特征
        // 特征：中等偏强信号（>2.0）、高方差（>8.0）、低DC分量（<3.0）
        // 关键区分：DC分量低（mean < 3.0）表示手机运动受限（口袋）
        // 注意：必须在 HAND_SWING 之前检测，因为两者都有高方差
        if (signalStrength > 2.0 && variance > 8.0 && dcComponent < 3.0) {
            return PhonePosition.PANTS_POCKET;
        }

        // 规则4: 手持摆动 - 需要非常高的方差 + 高DC分量（手臂自由摆动）
        // 手臂自然下垂摆动时，DC分量通常 > 4.0（重力影响明显）
        if (variance > 2.5 && signalStrength > 0.8 && dcComponent >= 3.0) {
            return PhonePosition.HAND_SWING;
        }

        // 默认分类：根据信号强度、方差和DC分量综合判断
        if (variance < 0.3) {
            if (signalStrength < 0.3) {
                return PhonePosition.HAND_HELD;
            } else {
                return PhonePosition.CHEST;
            }
        } else if (variance > 2.0) {
            if (dcComponent < 3.0) {
                return PhonePosition.PANTS_POCKET;
            } else {
                return PhonePosition.HAND_SWING;
            }
        } else {
            return PhonePosition.CHEST;
        }
    }

    /**
     * 获取位置描述
     */
    public static String getPositionDescription(PhonePosition position) {
        switch (position) {
            case HAND_HELD:
                return "手持静止";
            case HAND_SWING:
                return "手持摆动";
            case CHEST:
                return "胸前";
            case PANTS_POCKET:
                return "裤子口袋";
            case BAG:
                return "包里";
            default:
                return "未知";
        }
    }
}