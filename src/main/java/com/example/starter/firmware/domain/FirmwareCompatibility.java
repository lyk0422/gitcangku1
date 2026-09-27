package com.example.starter.firmware.domain;

/**
 * 固件兼容矩阵中的一条升级路径。
 *
 * @param id          兼容关系ID
 * @param model       设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param allowed     true 允许升级；false 显式不兼容（校验时拒绝）
 */
public record FirmwareCompatibility(long id, String model, String fromVersion, String toVersion,
                                    boolean allowed) {
}
