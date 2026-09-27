package com.example.starter.firmware.domain;

/**
 * 设备模块清单条目。
 *
 * @param module  模块名
 * @param version 模块版本，点分数字格式
 */
public record DeviceModuleEntry(String module, String version) {
}
