package com.example.starter.firmware.domain;

/**
 * 固件直接声明的模块需求。
 *
 * @param firmwareVersion 声明需求的目标固件版本
 * @param moduleName      所需模块名
 * @param minVersion      所需最小模块版本（点分数字）
 */
public record ModuleRequirement(String firmwareVersion, String moduleName, String minVersion) {
}
