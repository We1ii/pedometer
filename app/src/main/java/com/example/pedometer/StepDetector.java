package com.example.pedometer;

import java.util.ArrayList;
import java.util.List;

//计步算法核心（峰值检测)
public class StepDetector {
    private static final String TAG = "StepDetector";

    private KalmanFilter kalmanFilter;
    private List<Integer> stepIndices;
    private double thres;

    private double signalStrength;
    private int N;

    // 新增：当前检测到的手机位置
    private PositionDetector.PhonePosition currentPosition;

    // 新增：复用数组，减少内存分配
    private double[] filteredBuffer;
    private double[] enhancedBuffer;

    // 重叠区域过滤：只计数非重叠区域的峰值，避免同一步被两个窗口重复计数
    private int minPeakIndex;

    public StepDetector() {
        this.kalmanFilter = new KalmanFilter();
        this.stepIndices = new ArrayList<>();
        this.filteredBuffer = new double[200];  // 预分配足够空间
        this.enhancedBuffer = new double[200];
        this.currentPosition = PositionDetector.PhonePosition.UNKNOWN;
    }

    //计步算法核心流程 (StepDetector.detectSteps)
    //输入: double[100] accelerationData, int overlapSize (重叠窗口大小)
    //输出: int 检测到的步数
    public int detectSteps(double[] accelerationData, int overlapSize) {
        if (accelerationData == null || accelerationData.length < 10) {
            return 0;
        }

        // 设置重叠区域过滤：只计数 index >= overlapSize 的峰值
        // 避免同一步在两个窗口中被重复计数
        this.minPeakIndex = overlapSize;

        int dataLen = accelerationData.length;

        // 确保缓冲区足够大
        if (filteredBuffer.length < dataLen) {
            filteredBuffer = new double[dataLen];
            enhancedBuffer = new double[dataLen];
        }

        // 计算信号强度和噪声水平
        signalStrength = calculateSignalStrength(accelerationData);
        double noiseLevel = calculateNoiseLevel(accelerationData);

        // 识别手机位置
        currentPosition = PositionDetector.detectPosition(accelerationData);

        // 根据位置调整信噪比阈值
        double snrThreshold = getSNRThresholdForPosition(currentPosition);
        // 使用信噪比判断，而非绝对强度，从1.5提升到2.5，更严格地过滤噪声
        double snr = signalStrength / (noiseLevel + 1e-10);
        if (snr < snrThreshold) {  // 信噪比低于1.5认为是噪声
            return 0;
        }

        //阶段1: 卡尔曼滤波降噪（复用数组）--- 根据位置调整滤波器参数
        kalmanFilter.adjustParametersForPosition(currentPosition);
        double[] filteredData = kalmanFilter.filterToBuffer(accelerationData, filteredBuffer);

        //阶段2: 峰值增强（复用数组）
        double[] enhancedData = enhancePeaksToBuffer(filteredData, enhancedBuffer);

        stepIndices.clear();

        //阶段3: 计算阈值 (使用动态阈值，基于标准差) --- 根据位置动态调整
        thres = calculateThreshold(enhancedData);
        double minThres = getMinThresholdForPosition(currentPosition);
        //从0.7提升到1.2
        if (thres < minThres) {
            thres = minThres;
        }

        //阶段4: 查找过零点确定窗口大小N 找到第一个从负到正的过零点和从正到负的过零点
        int[] zeroCrossings = findZeroCrossings(enhancedData);
        if (zeroCrossings[0] == -1 || zeroCrossings[1] == -1) {
            return 0;
        }

        N = (int) Math.ceil(Math.abs(zeroCrossings[0] - zeroCrossings[1]) / 2.0);
        // 增大N的最小值和最大值，避免过小窗口导致多检
        // 根据位置调整N的范围
        int[] nRange = getNRangeForPosition(currentPosition);
        if (N < nRange[0]) {
            N = nRange[0];
        }
        if (N > nRange[1]) {
            N = nRange[1];
        }

        // 阶段5: 检测峰值
        detectPeaks(enhancedData);

        // 阶段6: 去除误检峰值 根据间距中位数过滤近距离峰值
        removeFalsePeaks(enhancedData);

        // 阶段6.5: 专门去除双峰（手持大幅度摆动时的子峰值）
        if (currentPosition == PositionDetector.PhonePosition.HAND_SWING) {
            removeDoublePeaks(enhancedData);
        }

        // 新增：合理性检查，2秒内最多6步（对应180步/分钟的跑步速度）
        // 合理性检查：根据位置调整最大步数
        int maxSteps = getMaxStepsForPosition(currentPosition);
        if (stepIndices.size() > maxSteps) {
            // 如果检测到超过6步，很可能是误检，只保留最强的6个峰值
            keepStrongestPeaks(enhancedData, maxSteps);
        }

        return stepIndices.size();
    }

    /**
     * 根据位置获取信噪比阈值
     */
    private double getSNRThresholdForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                return 1.5;  // 口袋：最低阈值，允许更多信号通过
            case CHEST:
                return 1.8;  // 胸前：较低阈值
            case HAND_SWING:
                return 2.0;  // 手持摆动：中等阈值
            case HAND_HELD:
                return 2.5;  // 手持静止：较高阈值
            case BAG:
                return 2.2;  // 包里：中等偏高
            default:
                return 2.0;  // 未知：默认
        }
    }

    /**
     * 根据位置获取最小阈值
     */
    private double getMinThresholdForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                return 0.4;  // 口袋：最低阈值
            case CHEST:
                return 0.6;  // 胸前：较低阈值
            case HAND_SWING:
                return 0.9;  // 手持摆动：恢复原始值，用双峰检测代替
            case HAND_HELD:
                return 1.0;  // 手持静止：较高阈值
            case BAG:
                return 0.8;  // 包里：中等
            default:
                return 0.7;  // 未知：默认
        }
    }

    /**
     * 根据位置获取N的范围 [min, max]
     */
    private int[] getNRangeForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                return new int[]{6, 8};  // 口袋：大窗口，减少漏检
            case CHEST:
                return new int[]{3, 7};  // 胸前：中小窗口
            case HAND_SWING:
                return new int[]{3, 5};  // 手持摆动：恢复原始值
            case HAND_HELD:
                return new int[]{4, 8};  // 手持静止：中等窗口
            case BAG:
                return new int[]{6, 8};  // 包里：与口袋相同
            default:
                return new int[]{3, 7};  // 未知：默认
        }
    }

    /**
     * 根据位置获取单窗口最大步数
     */
    private int getMaxStepsForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                return 8;  // 口袋：大窗口允许更多步数

            case CHEST:
                return 5;  // 胸前：降低到5

            case HAND_SWING:
                return 5;  // 手持摆动：恢复原始值

            case HAND_HELD:
                return 4;  // 手持静止：降低到4（通常不应该有很多步）

            case BAG:
                return 8;  // 包里：与口袋相同

            default:
                return 5;  // 未知：默认5
        }
    }

    private double calculateSignalStrength(double[] data) {
        double sum = 0;
        for (double v : data) {
            sum += v * v;
        }
        return Math.sqrt(sum / data.length);
    }

    // 新增：计算噪声水平（使用差分法）
    private double calculateNoiseLevel(double[] data) {
        if (data.length < 2) return 0;

        double sumDiffSq = 0;
        for (int i = 1; i < data.length; i++) {
            double diff = data[i] - data[i - 1];
            sumDiffSq += diff * diff;
        }
        // 噪声估计：相邻差分的一半
        return Math.sqrt(sumDiffSq / (2 * (data.length - 1)));
    }

    private double[] enhancePeaks(double[] data) {
        double[] enhanced = new double[data.length];

        // 使用连续函数动态计算增强指数
        // signalStrength范围通常在0.1~2.0之间
        // 映射到指数范围：弱信号2.0，强信号1.5
        double exponent;
        if (signalStrength < 0.3) {
            // 弱信号（胸前）：使用更强的非线性增强（平方）
            exponent = 2.0;
        } else if (signalStrength > 1.0) {
            exponent = 1.5;
        } else {
            // 线性插值：0.3->2.0, 1.0->1.5
            exponent = 2.0- (signalStrength - 0.3) / (1.0 - 0.3) * 0.5;
        }

        // 使用立方根增强峰值，比平方更温和，减少小波动的放大
        for (int i = 0; i < data.length; i++) {
            double absVal = Math.abs(data[i]);
            //非线性增强 越大的数被放得越大，小信号变化不大 同时保留原来的正负方向。
            enhanced[i] = Math.pow(absVal, exponent) * Math.signum(data[i]);
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

    private double[] enhancePeaksToBuffer(double[] data, double[] output) {
        // 使用连续函数动态计算增强指数
        double exponent;

        switch (currentPosition) {
            case PANTS_POCKET:
                // 口袋：最强增强（但降低一点，避免过度检测）
                exponent = 2.0;
                break;
            case CHEST:
                // 胸前：较强增强
                exponent = 1.7;
                break;
            case HAND_SWING:
                // 手持摆动：恢复原始值
                exponent = 1.1;
                break;
            case HAND_HELD:
                // 手持静止：最温和
                exponent = 1.2;
                break;
            case BAG:
                // 包里：较强增强
                exponent = 1.8;
                break;
            default:
                // 未知：根据信号强度
                if (signalStrength < 0.4) {
                    exponent = 1.8;
                } else if (signalStrength > 1.0) {
                    exponent = 1.3;
                } else {
                    exponent = 1.5;
                }
        }

        for (int i = 0; i < data.length; i++) {
            double absVal = Math.abs(data[i]);
            output[i] = Math.pow(absVal, exponent) * Math.signum(data[i]);
        }

        // 平滑处理
        if (data.length > 5) {
            double[] temp = new double[data.length];

            // 首尾特殊处理
            temp[0] = output[0];
            temp[1] = (output[0] + output[1] * 2 + output[2]) / 4.0;
            temp[data.length - 2] = (output[data.length - 3] + output[data.length - 2] * 2 + output[data.length - 1]) / 4.0;
            temp[data.length - 1] = output[data.length - 1];

            // 口袋用3点平滑（保留细节），其他用5点平滑
            int smoothWindow;
            if (currentPosition == PositionDetector.PhonePosition.PANTS_POCKET) {
                smoothWindow = 3;
            } else {
                smoothWindow = 5;
            }

            if (smoothWindow == 3) {
                for (int i = 2; i < data.length - 2; i++) {
                    temp[i] = (output[i - 1] + output[i] * 2 + output[i + 1]) / 4.0;
                }
            } else {
                for (int i = 2; i < data.length - 2; i++) {
                    temp[i] = (output[i - 2] + output[i - 1] * 2 + output[i] * 3 + output[i + 1] * 2 + output[i + 2]) / 10.0;
                }
            }

            // 复制到output
            System.arraycopy(temp, 0, output, 0, data.length);
        }

        return output;
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

        // 根据位置调整阈值系数
        double coefficient;
        switch (currentPosition) {
            case PANTS_POCKET:
                coefficient = 0.75;  // 口袋：提高阈值，减少误检
                break;
            case CHEST:
                coefficient = 0.85;  // 胸前：提高阈值
                break;
            case HAND_SWING:
                coefficient = 1.15;  // 手持摆动：恢复原始值
                break;
            case HAND_HELD:
                coefficient = 1.0;   // 手持静止：最严格
                break;
            case BAG:
                coefficient = 0.8;   // 包里：提高阈值
                break;
            default:
                coefficient = 0.9;   // 未知：默认提高
        }

        return mean + coefficient * stdDev;
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
        // 起始索引取 N 和 minPeakIndex 的较大值
        // minPeakIndex = 重叠窗口大小，确保只检测非重叠区域的峰值
        for (int i = Math.max(N, minPeakIndex); i < data.length - N; i++) {
            if (data[i] > thres) {
                //初步必须满足大于阈值
                boolean isPeak = true;

                for (int j = 1; j <= N; j++) {
                    // 修复bug：应该是 data[i + j] 而不是 data[i + 1]
                    // 同时使用 <= 而不是 <，避免平台型峰值被误检
                    if (data[i] < data[i - j] || data[i] < data[i + j]) {
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

        // 根据位置调整阈值系数（提高系数，加强近距离峰值过滤）
        double thresholdRatio;
        switch (currentPosition) {
            case PANTS_POCKET:
                thresholdRatio = 0.65;   // 口袋：放宽过滤，保留更多峰值（避免漏检）
                break;
            case CHEST:
                thresholdRatio = 0.80;   // 胸前：（原0.70，提高到0.80）
                break;
            case HAND_SWING:
                thresholdRatio = 0.90;   // 手持摆动：恢复原始值
                break;
            case HAND_HELD:
                thresholdRatio = 0.90;   // 手持静止：（原0.85，提高到0.90）
                break;
            case BAG:
                thresholdRatio = 0.65;   // 包里：与口袋相同
                break;
            default:
                thresholdRatio = 0.80;   // 未知：（原0.75，提高到0.80）
        }

        // 增加绝对最小距离：根据位置调整
        // 口袋/手持摆动：8个样本 = 160ms（允许更快的步频）
        // 其他模式：10个样本 = 200ms（人类极限）
        int minDistance = (currentPosition == PositionDetector.PhonePosition.PANTS_POCKET ||
                           currentPosition == PositionDetector.PhonePosition.HAND_SWING) ? 8 : 10;
        double threshold = Math.max(medianDist * thresholdRatio, minDistance);

        //误检的峰值索引下标（需要剔除的）
        List<Integer> toRemove = new ArrayList<>();

        //④ 如果峰值间的间距 < 阈值 (如果两个"峰值"距离太近（比如只有3-5个点），说明其中一个是误检)
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] < threshold) {
                // 保留峰值更高的那个
                double peak1 = data[stepIndices.get(i)];
                double peak2 = data[stepIndices.get(i + 1)];

                // 口袋、包里和手持摆动模式：只在差异非常明显时才去除（避免漏检）
                if (currentPosition == PositionDetector.PhonePosition.PANTS_POCKET ||
                        currentPosition == PositionDetector.PhonePosition.BAG) {
                    if (Math.abs(peak1 - peak2) > thres * 1.0) {
                        if (peak1 > peak2) {
                            toRemove.add(stepIndices.get(i + 1));
                        } else {
                            toRemove.add(stepIndices.get(i));
                        }
                    }
                } else {
                    // 其他模式：正常去除较小的
                    if (peak1 > peak2) {
                        toRemove.add(stepIndices.get(i + 1));
                    } else {
                        toRemove.add(stepIndices.get(i));
                    }
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

    /**
     * 专门去除双峰（手持大幅度摆动时产生的子峰值）
     * 双峰特征：两个峰值距离较近（<15样本），且第二个峰值明显小于第一个
     */
    private void removeDoublePeaks(double[] data) {
        if (stepIndices.size() < 2) {
            return;
        }

        // 双峰判定条件：
        // 1. 距离 < 15样本（300ms）- 大幅度摆动双峰间距约12-15样本
        // 2. 第二个峰值 < 第一个峰值的80% - 子峰明显小于主峰
        int maxDoublePeakDistance = 15;
        double peakRatioThreshold = 0.8;

        List<Integer> filteredIndices = new ArrayList<>();
        int i = 0;

        while (i < stepIndices.size()) {
            int currentIdx = stepIndices.get(i);
            double currentPeak = data[currentIdx];

            // 检查下一个峰值是否与当前峰值构成双峰
            if (i + 1 < stepIndices.size()) {
                int nextIdx = stepIndices.get(i + 1);
                int dist = nextIdx - currentIdx;

                if (dist <= maxDoublePeakDistance) {
                    double nextPeak = data[nextIdx];

                    // 如果下一个峰值明显较小，跳过它（它是子峰）
                    if (nextPeak < currentPeak * peakRatioThreshold) {
                        filteredIndices.add(currentIdx);  // 保留当前（主峰）
                        i += 2;  // 跳过下一个（子峰）
                        continue;
                    }
                    // 如果当前峰值明显较小，跳过当前（它是子峰）
                    else if (currentPeak < nextPeak * peakRatioThreshold) {
                        filteredIndices.add(nextIdx);  // 保留下一个（主峰）
                        i += 2;  // 跳过当前和下一个
                        continue;
                    }
                }
            }

            // 正常情况，保留当前峰值
            filteredIndices.add(currentIdx);
            i++;
        }

        // 重建stepIndices
        stepIndices.clear();
        stepIndices.addAll(filteredIndices);
    }

    // 新增：保留最强的N个峰值
    private void keepStrongestPeaks(double[] data, int maxCount) {
        if (stepIndices.size() <= maxCount) {
            return;
        }

        // 创建峰值强度列表
        List<int[]> peakList = new ArrayList<>();
        for (int i = 0; i < stepIndices.size(); i++) {
            int idx = stepIndices.get(i);
            peakList.add(new int[]{idx, i});  // [索引位置, 在stepIndices中的位置]
        }

        // 按峰值强度排序（从大到小）
        peakList.sort((a, b) -> Double.compare(data[b[0]], data[a[0]]));

        // 保留前maxCount个最强的峰值
        List<Integer> toKeep = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            toKeep.add(peakList.get(i)[1]);  // 保留在stepIndices中的位置
        }
        toKeep.sort(Integer::compareTo);  // 按时间顺序排序

        // 重建stepIndices
        List<Integer> newStepIndices = new ArrayList<>();
        for (int pos : toKeep) {
            newStepIndices.add(stepIndices.get(pos));
        }

        stepIndices.clear();
        stepIndices.addAll(newStepIndices);
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
        currentPosition = PositionDetector.PhonePosition.UNKNOWN;
    }

    /**
     * 获取当前位置
     */
    public PositionDetector.PhonePosition getCurrentPosition() {
        return currentPosition;
    }
}