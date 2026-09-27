package com.example.starter.firmware.domain;

/**
 * 固件版本所需模块及最小版本（兼容矩阵当前态）。
 *
 * @param firmwareVersion 固件版本（发布单目标版本）
 * @param module          所需模块名
 * @param minVersion      最小模块版本，点分数字格式
 */
public record FirmwareRequirement(String firmwareVersion, String module, String minVersion) {
}
