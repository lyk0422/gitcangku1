package com.example.starter.firmware.domain;

/**
 * 设备上报的单个模块版本摘要。
 *
 * @param moduleName 模块名
 * @param version    模块版本（点分数字）
 */
public record DeviceModule(String moduleName, String version) {
}
