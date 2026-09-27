package com.example.starter.firmware.domain;

/**
 * 设备模块清单的固化条目，用于任务快照与兼容判定证据。
 *
 * @param moduleName 模块名
 * @param version    上报版本，点分数字
 * @param digest     版本摘要，原样保存
 */
public record ModuleSnapshot(String moduleName, String version, String digest) {
}
