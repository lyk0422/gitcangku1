package com.example.starter.firmware.api;

/**
 * 发布预检视图：按设备可投放集合计算候选设备数与隔离设备数。
 *
 * @param model            目标设备型号
 * @param fromVersion      来源固件版本
 * @param ratio            投放比例，分桶号小于该值的设备为候选
 * @param candidateCount   候选设备总数（含隔离设备）
 * @param quarantinedCount 候选中处于 QUARANTINED 的设备数
 * @param deployableCount  可投放设备数（ACTIVE 且在该发布单下尚无任务）
 * @param launchable       是否可以启动：可投放设备数大于 0
 */
public record PrecheckView(String model, String fromVersion, int ratio, long candidateCount,
                           long quarantinedCount, long deployableCount, boolean launchable) {
}
