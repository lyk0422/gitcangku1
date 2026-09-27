package com.example.starter.firmware.domain;

/**
 * 兼容校验缺失项：缺失模块或设备模块版本低于最低要求。
 *
 * @param moduleName      模块名（稳定排序键）
 * @param deviceVersion   设备当前模块版本；设备未安装该模块为 null
 * @param requiredVersion 最低要求版本（点分数字）
 */
public record MissingModule(String moduleName, String deviceVersion, String requiredVersion) {
}
