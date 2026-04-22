package com.example.pedometer;

//自适应卡尔曼滤波器（降噪）
public class KalmanFilter {
    private double xHat;
    private double P;
    private final double QBase;
    private final double RBase;
    private final int winSize;
    private double[] dataWindow;
    private int windowIndex;
    private boolean isInitialized;

    public KalmanFilter() {
        this(0.05, 0.2, 5);
    }

    public KalmanFilter(double qBase, double rBase, int winSize) {
        this.QBase = qBase;
        this.RBase = rBase;
        this.winSize = winSize;
        this.dataWindow = new double[winSize];
        this.windowIndex = 0;
        this.isInitialized = false;
    }

    //阶段1: 卡尔曼滤波降噪
    public double[] filter(double[] data) {
        if (data == null || data.length == 0) {
            return new double[0];
        }

        //① 预处理: 3σ原则剔除异常值
        double[] data0 = preprocessData(data);
        int n0 = data0.length;
        double[] data2 = new double[n0];

        int initWin = Math.min(winSize, n0);
        //② 初始化: 用前5个数据均值初始化 xHat
        xHat = calculateMean(data0, 0, initWin);
        P = 1.0;

        for (int i = 0; i < initWin; i++) {
            data2[i] = xHat;
        }

        //③ 自适应滤波: 根据局部方差动态调整 Q 和 R
        for (int k = initWin; k < n0; k++) {
            int winStart = Math.max(0, k - winSize);
            // 根据数据局部方差动态调整Q和R
            double localVar = calculateVariance(data0, winStart, k);

            double Q = QBase * (1 + localVar); // 方差大→Q增大→更相信新数据
            double R = RBase / (1 + localVar); // 方差大→R减小→降低测量噪声权重 更相信估计值

            double xHatMinus = xHat;
            double PMinus = P + Q;

            double K = PMinus / (PMinus + R);
            xHat = xHatMinus + K * (data0[k] - xHatMinus);
            P = (1 - K) * PMinus;

            data2[k] = xHat;
        }

        // ④ 后处理: 3点滑动平均进一步平滑
        data2 = smoothData(data2, 3);

        return data2;
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

    public void reset() {
        isInitialized = false;
        windowIndex = 0;
    }
}
