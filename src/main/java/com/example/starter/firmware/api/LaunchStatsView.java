package com.example.starter.firmware.api;

/**
 * 投放失败率统计：只统计已创建任务（兼容设备），不兼容排除设备不计入。
 *
 * @param taskCount          任务总数（兼容设备数）
 * @param successCount       已成功任务数
 * @param failedCount        已失败任务数
 * @param excludedCount      不兼容排除设备数，不计入失败率
 * @param failureRatePercent 失败率百分比，failedCount*100/taskCount；无任务时为 0
 */
public record LaunchStatsView(long taskCount, long successCount, long failedCount,
                              long excludedCount, long failureRatePercent) {

    public static LaunchStatsView of(long taskCount, long successCount, long failedCount,
                                     long excludedCount) {
        long rate = taskCount == 0 ? 0 : failedCount * 100 / taskCount;
        return new LaunchStatsView(taskCount, successCount, failedCount, excludedCount, rate);
    }
}
