package com.example.pedometer;

//自适应卡尔曼滤波器（降噪）
public class KalmanFilter {
    private double xHat;
    private double P;
    private final double QBase;
    private final double RBase;
    private double QBaseAdj;  // 位置调整后的Q基值
    private double RBaseAdj;  // 位置调整后的R基值
    private final int winSize;
    private double[] dataWindow;
    private int windowIndex;
    private boolean isInitialized;

//    public KalmanFilter() {
//        this(0.02, 0.25, 5);
//    }

    public KalmanFilter() {
        this(0.03, 0.22, 5);
    }

    public KalmanFilter(double qBase, double rBase, int winSize) {
        this.QBase = qBase;
        this.RBase = rBase;
        this.QBaseAdj = qBase;
        this.RBaseAdj = rBase;
        this.winSize = winSize;
        this.dataWindow = new double[winSize];
        this.windowIndex = 0;
        this.isInitialized = false;
    }

    //阶段1: 卡尔曼滤波降噪
//    public double[] filter(double[] data) {
//        if (data == null || data.length == 0) {
//            return new double[0];
//        }
//
//        //① 预处理: 3σ原则剔除异常值
//        double[] data0 = preprocessData(data);
//        int n0 = data0.length;
//        double[] data2 = new double[n0];
//
//        int initWin = Math.min(winSize, n0);
//        //② 初始化: 用前5个数据均值初始化 xHat
//        xHat = calculateMean(data0, 0, initWin);
//        P = 1.0;
//
//        for (int i = 0; i < initWin; i++) {
//            data2[i] = xHat;
//        }
//
//        //③ 自适应滤波: 根据局部方差动态调整 Q 和 R
//        for (int k = initWin; k < n0; k++) {
//            int winStart = Math.max(0, k - winSize);
//            // 根据数据局部方差动态调整Q和R
//            double localVar = calculateVariance(data0, winStart, k);
//
//            double Q = QBase * (1 + localVar * 0.9); // 方差大→Q增大→更相信新数据
//            double R = RBase / (1 + localVar * 0.6); // 方差大→R减小→降低测量噪声权重 更相信估计值
//
//            double xHatMinus = xHat;
//            double PMinus = P + Q;
//
//            double K = PMinus / (PMinus + R);
//            xHat = xHatMinus + K * (data0[k] - xHatMinus);
//            P = (1 - K) * PMinus;
//
//            data2[k] = xHat;
//        }
//
//        // 移除额外的滑动平均平滑，卡尔曼滤波已经是最优估计
//        // ④ 后处理: 3点滑动平均进一步平滑
//        // data2 = smoothData(data2, 3);
//
//        return data2;
//    }
    public double[] filter(double[] data) {
        double[] output = new double[data.length];
        return filterToBuffer(data, output);
    }

    private double[] preprocessData(double[] data) {
        double[] result = new double[data.length];
        double sum = 0;
        for (double v : data) sum += v;
        double mean = sum / data.length;

        double sumSq = 0;
        for (double v : data) {
            sumSq += (v - mean) * (v - mean);
        }

        //标准差
        double sigma = Math.sqrt(sumSq / data.length);

        // 3σ原则：剔除异常值
        double threshold = 3 * sigma;
        for (int i = 0; i < data.length; i++) {
            if (Math.abs(data[i] - mean) > threshold) {
                // 异常值替换为均值
                result[i] = mean;
            } else {
                result[i] = data[i];
            }
        }

        return result;
    }

    //计算前5个数平均值
    private double calculateMean(double[] data, int start, int end) {
        double sum = 0;
        int count = end - start;
        for (int i = start; i < end; i++) {
            sum += data[i];
        }
        return count > 0 ? sum / count : 0;
    }

    private double calculateVariance(double[] data, int start, int end) {
        double mean = calculateMean(data, start, end);
        double sumSq = 0;
        int count = end - start;
        for (int i = start; i < end; i++) {
            sumSq += (data[i] - mean) * (data[i] - mean);
        }
        return count > 1 ? sumSq / (count - 1) : 0;
    }

    private double[] smoothData(double[] data, int window) {
        if (data.length <= window) {
            return data;
        }

        double[] smoothed = new double[data.length];
        int halfWin = window / 2;

        for (int i = 0; i < data.length; i++) {
            int start = Math.max(0, i - halfWin);
            int end = Math.min(data.length, i + halfWin + 1);
            smoothed[i] = calculateMean(data, start, end);
        }

        return smoothed;
    }

    /**
     * 根据手机位置调整滤波器参数
     * 口袋：信号弱→更平滑， 静止→→更少跟踪噪声
     * 手持摆动→标准参数
     */
    public void adjustParametersForPosition(PositionDetector.PhonePosition position) {
        switch (position) {
            case PANTS_POCKET:
                // 口袋：信号弱且噪声多，更平滑、更不信任测量
                this.QBaseAdj = QBase * 0.6;   // 降低过程噪声估计→更平滑
                this.RBaseAdj = RBase * 1.5;   // 增大测量噪声估计→更相信预测
                break;
            case CHEST:
                // 胸前：中等参数，稍偏平滑
                this.QBaseAdj = QBase * 0.8;
                this.RBaseAdj = RBase * 1.2;
                break;
            case HAND_SWING:
                // 手持摆动：信号强，标准参数
                this.QBaseAdj = QBase;
                this.RBaseAdj = RBase;
                break;
            case HAND_HELD:
                // 手持静止：最强平滑（避免噪声被误检为步态）
                this.QBaseAdj = QBase * 0.4;
                this.RBaseAdj = RBase * 2.0;
                break;
            case BAG:
                // 包里：类似口袋
                this.QBaseAdj = QBase * 0.7;
                this.RBaseAdj = RBase * 1.3;
                break;
            default:
                // 未知：默认参数
                this.QBaseAdj = QBase;
                this.RBaseAdj = RBase;
                break;
        }
    }

    // 新增：输出到预分配缓冲区
    public double[] filterToBuffer(double[] data, double[] output) {
        if (data == null || data.length == 0) {
            return new double[0];
        }

        //① 预处理: 3σ原则剔除异常值
        double[] data0 = preprocessData(data);
        int n0 = data0.length;

        int initWin = Math.min(winSize, n0);
        //② 初始化: 用前5个数据均值初始化 xHat
        xHat = calculateMean(data0, 0, initWin);
        P = 1.0;

        for (int i = 0; i < initWin; i++) {
            output[i] = xHat;
        }

        //③ 自适应滤波: 根据局部方差动态调整 Q 和 R
        for (int k = initWin; k < n0; k++) {
            int winStart = Math.max(0, k - winSize);
            double localVar = calculateVariance(data0, winStart, k);

            double Q = QBaseAdj * (1 + localVar * 0.7);
            double R = RBaseAdj / (1 + localVar * 0.4);

            double xHatMinus = xHat;
            double PMinus = P + Q;

            double K = PMinus / (PMinus + R);
            xHat = xHatMinus + K * (data0[k] - xHatMinus);
            P = (1 - K) * PMinus;

            output[k] = xHat;
        }

        // 恢复3点滑动平均，但改用5点平滑
        output = smoothData(output, 3);

        return output;
    }

    public void reset() {
        isInitialized = false;
        windowIndex = 0;
    }
}