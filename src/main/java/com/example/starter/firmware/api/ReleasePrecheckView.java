package com.example.starter.firmware.api;

/**
 * 发布预检视图（只读）：按型号与来源版本计算候选设备集合与可投放集合。
 * startable 为 false 表示候选设备全部隔离，启动发布单将返回 422。
 *
 * @param model            目标设备型号
 * @param fromVersion      来源固件版本
 * @param candidateCount   候选设备数（型号与当前版本匹配）
 * @param quarantinedCount 候选设备中已隔离数
 * @param deliverableCount 可投放设备数（候选且未隔离）
 * @param startable        是否可启动：无候选设备或存在可投放设备
 */
public record ReleasePrecheckView(String model, String fromVersion, long candidateCount,
                                  long quarantinedCount, long deliverableCount, boolean startable) {
}
