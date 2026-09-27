package com.example.starter.firmware.domain;

/**
 * 固件兼容矩阵记录：某型号从 fromVersion 到 toVersion 是否允许投放。
 *
 * @param id          记录ID
 * @param model       设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param allowed     true 允许；false 明确拒绝
 * @param updatedBy   最近操作者
 */
public record CompatRule(long id, String model, String fromVersion, String toVersion,
                         boolean allowed, String updatedBy) {
}
