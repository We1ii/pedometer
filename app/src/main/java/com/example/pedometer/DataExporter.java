package com.example.pedometer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据导出类，负责将加速度数据导出为XLS文件
 */
public class DataExporter {
    private static final String TAG = "DataExporter";
    private static final String FOLDER_NAME = "PedometerData";
    
    /**
     * 加速度数据项
     */
    public static class AccelerometerData {
        public float x, y, z;
        public long timestamp;
        public String position;  // 检测到的姿态

        public AccelerometerData(float x, float y, float z, long timestamp, String position) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
            this.position = position;
        }
    }
    
    /**
     * 导出数据到XLS文件
     * 
     * @param context 应用上下文
     * @param data 加速度数据列表
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param samplingFrequency 采样频率
     * @param stepCount 步数
     * @return 导出的文件路径，失败返回null
     */
    public static String exportToExcel(Context context, List<AccelerometerData> data, 
                                     long startTime, long endTime, int samplingFrequency, int stepCount) {
        Log.i(TAG, "开始导出数据，数据条数: " + data.size());
        
        try {
            // 创建保存目录
            File directory = getDataDirectory(context);
            if (directory == null) {
                Log.e(TAG, "无法创建数据目录");
                return null;
            }
            
            Log.i(TAG, "数据目录: " + directory.getAbsolutePath());
            
            // 生成文件名
            String fileName = generateFileName();
            File file = new File(directory, fileName);
            
            Log.i(TAG, "准备创建文件: " + file.getAbsolutePath());
            
            // 创建XLS文件
            FileOutputStream outputStream = new FileOutputStream(file);
            
            // 填充数据
            fillXLSData(outputStream, data, startTime, endTime, samplingFrequency, stepCount);
            
            // 保存文件
            outputStream.close();
            
            // 验证文件是否创建成功
            if (file.exists()) {
                Log.i(TAG, "数据导出成功: " + file.getAbsolutePath() + ", 文件大小: " + file.length() + " bytes");
                return file.getAbsolutePath();
            } else {
                Log.e(TAG, "文件创建失败");
                return null;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "导出数据失败", e);
            return null;
        }
    }
    
    /**
     * 填充XLS数据（生成真正的Excel BIFF5格式，MATLAB完全支持）
     */
    private static void fillXLSData(FileOutputStream outputStream, List<AccelerometerData> data, 
                                   long startTime, long endTime, int samplingFrequency, int stepCount) throws IOException {
        
        Log.i(TAG, "开始填充XLS数据，数据条数: " + data.size());
        
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy_HH.mm", Locale.getDefault());
        DecimalFormat decimalFormat = new DecimalFormat("0.000"); // 保留三位小数
        
        try {
            // 使用制表符分隔格式，Excel会正确识别为列分隔
            StringBuilder tsvContent = new StringBuilder();
            
            // 写入数据行
            for (int i = 0; i < data.size(); i++) {
                AccelerometerData item = data.get(i);

                // 第一列：x 轴数据（保留三位小数）
                tsvContent.append(decimalFormat.format(item.x)).append("\t");

                // 第二列：y 轴数据
                tsvContent.append(decimalFormat.format(item.y)).append("\t");

                // 第三列：z 轴数据
                tsvContent.append(decimalFormat.format(item.z)).append("\t");

                // 第四列：姿态
                tsvContent.append(item.position != null ? item.position : "未知").append("\t");

                // 第五列：13位毫秒级时间戳
                tsvContent.append(item.timestamp).append("\t");

                // 第六列空着
                tsvContent.append("\t");

                // 第七列：逐行存放信息
                if (i == 0) {
                    tsvContent.append("Start Time：").append(dateFormat.format(new Date(startTime)));
                } else if (i == 1) {
                    tsvContent.append("End Time：").append(dateFormat.format(new Date(endTime)));
                } else if (i == 2) {
                    tsvContent.append("Sampling frequency: ").append(samplingFrequency).append(" hz");
                } else if (i == 3) {
                    tsvContent.append("Number of data：").append(data.size());
                } else if (i == 4) {
                    tsvContent.append("Step count:").append(stepCount);
                }

                tsvContent.append("\n");
            }
            
            // 写入文件
            outputStream.write(tsvContent.toString().getBytes("UTF-8"));
            
            Log.i(TAG, "XLS数据填充完成，使用制表符分隔格式，总行数: " + data.size());
        } catch (Exception e) {
            Log.e(TAG, "填充XLS数据时出错", e);
            throw new IOException("生成Excel文件失败", e);
        }
    }
    
    /**
     * 写入BIFF5文件头
     */
    private static void writeBIFF5Header(FileOutputStream outputStream) throws IOException {
        // BOF记录 (Beginning of File)
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x0809); // BOF记录类型
        buffer.putShort((short) 4);       // 记录长度
        buffer.putShort((short) 0x0005);  // BIFF5版本
        buffer.putShort((short) 0x0005);  // 工作表子类型
        outputStream.write(buffer.array());
    }
    
    /**
     * 写入BIFF5数字单元格
     */
    private static void writeBIFF5NumberCell(FileOutputStream outputStream, int row, int col, double value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(18);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x0203); // NUMBER记录类型
        buffer.putShort((short) 14);      // 记录长度
        buffer.putShort((short) row);     // 行号
        buffer.putShort((short) col);     // 列号
        buffer.putShort((short) 0);       // XF记录索引
        buffer.putDouble(value);          // IEEE 754双精度浮点数
        outputStream.write(buffer.array());
    }
    
    /**
     * 写入BIFF5文本单元格
     */
    private static void writeBIFF5LabelCell(FileOutputStream outputStream, int row, int col, String text) throws IOException {
        byte[] textBytes = text.getBytes("ISO-8859-1"); // BIFF5使用Latin-1编码
        int textLength = Math.min(textBytes.length, 255); // BIFF5最大255字符
        
        ByteBuffer buffer = ByteBuffer.allocate(9 + textLength);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x0204); // LABEL记录类型
        buffer.putShort((short) (7 + textLength)); // 记录长度
        buffer.putShort((short) row);     // 行号
        buffer.putShort((short) col);     // 列号
        buffer.putShort((short) 0);       // XF记录索引
        buffer.put((byte) textLength);    // 字符串长度
        buffer.put(textBytes, 0, textLength); // 字符串内容
        outputStream.write(buffer.array());
    }
    
    /**
     * 写入BIFF5文件尾
     */
    private static void writeBIFF5Footer(FileOutputStream outputStream) throws IOException {
        // EOF记录 (End of File)
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x000A); // EOF记录类型
        buffer.putShort((short) 0);       // 记录长度
        outputStream.write(buffer.array());
    }
    
    /**
     * 获取数据保存目录
     */
    public static File getDataDirectory(Context context) {
        File directory;
        
        // 优先使用外部存储的应用专用目录
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            directory = new File(context.getExternalFilesDir(null), FOLDER_NAME);
        } else {
            // 使用内部存储
            directory = new File(context.getFilesDir(), FOLDER_NAME);
        }
        
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                Log.e(TAG, "无法创建目录: " + directory.getAbsolutePath());
                return null;
            }
        }
        
        return directory;
    }
    
    /**
     * 生成文件名
     * 格式：stepcount_2025.11.13_19:04:35.xls
     */
    private static String generateFileName() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd_HH:mm:ss", Locale.getDefault());
        String timestamp = dateFormat.format(new Date());
        return "stepcount_" + timestamp + ".xls";
    }
}