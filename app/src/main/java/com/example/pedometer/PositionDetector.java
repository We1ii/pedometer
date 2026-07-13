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

    // 位置判断阈值
    private static final double SWING_VARIANCE_THRESHOLD = 2.5;  // 摆动方差阈值（手臂钟摆运动方差极大，从1.2提高到2.5，与胸前拉开距离）
    private static final double SIGNAL_STRENGTH_WEAK = 0.2;      // 弱信号阈值（从0.3降到0.2）
    private static final double SIGNAL_STRENGTH_MEDIUM = 0.6;    // 中等信号阈值
    private static final double SIGNAL_STRENGTH_STRONG = 1.0;    // 强信号阈值
    private static final double DC_COMPONENT_HIGH = 6.0;         // 高直流分量（垂直向下/向上）（从8.0降到6.0）
    private static final double DC_COMPONENT_LOW = 5.0;          // 低直流分量（水平放置）（从3.0提高到5.0）


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
        return classifyPosition(signalStrength, variance, peakFrequency, dcComponent);
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
                                                  double dcComponent) {

        // 规则1: 极低方差 = 静止状态（手持静止或放在桌上）
        if (variance < 0.15) {
            return PhonePosition.HAND_HELD;
        }

        // 规则2: 手持摆动 - 需要非常高的方差（手臂钟摆运动产生极大加速度）
        // 手臂自然下垂摆动时，方差通常在 2.5-8.0+，远高于胸前走路晃动的 0.5-1.8
        if (variance > SWING_VARIANCE_THRESHOLD &&
                peakFrequency >= 3 && peakFrequency <= 12 &&
                signalStrength > 0.8) {
            return PhonePosition.HAND_SWING;
        }

        // 规则3: 胸前 - 中等方差（走路时身体上下晃动），信号中等，DC分量低（手机较水平）
        // 扩大方差范围到 0.15-2.0，覆盖走路时胸前晃动（方差可达 0.8-1.5+）
        if (signalStrength >= SIGNAL_STRENGTH_WEAK &&
                signalStrength <= 1.5 &&
                variance >= 0.15 && variance <= 2.0 &&
                dcComponent < DC_COMPONENT_LOW) {
            return PhonePosition.CHEST;
        }

        // 规则4: 裤子口袋 - 弱信号 + 低方差 + 高DC分量（手机垂直放置）
        if (signalStrength < SIGNAL_STRENGTH_MEDIUM &&
                variance < 0.8 &&
                dcComponent > DC_COMPONENT_HIGH) {
            return PhonePosition.PANTS_POCKET;
        }

        // 规则5: 包里 - 强信号 + 高方差（不规律晃动）
        if (signalStrength > SIGNAL_STRENGTH_STRONG &&
                variance > 1.5) {
            return PhonePosition.BAG;
        }

        // 默认分类：根据信号强度、方差和DC分量综合判断
        if (variance < 0.3) {
            // 低方差：静止状态
            if (signalStrength < 0.3) {
                return PhonePosition.HAND_HELD;
            } else {
                return PhonePosition.CHEST;
            }
        } else if (variance > 2.0) {
            // 高方差：运动状态，需要更仔细区分
            if (signalStrength > 0.8 && peakFrequency >= 3) {
                return PhonePosition.HAND_SWING;
            } else {
                return PhonePosition.BAG;
            }
        } else {
            // 中等方差
            if (dcComponent > DC_COMPONENT_HIGH) {
                return PhonePosition.PANTS_POCKET;
            } else {
                return PhonePosition.CHEST;
            }
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