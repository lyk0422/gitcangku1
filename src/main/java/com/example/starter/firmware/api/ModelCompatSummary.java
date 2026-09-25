package com.example.starter.firmware.api;

/**
 * 发布预检中单个硬件型号的候选设备汇总。
 *
 * @param hardwareModel 硬件型号
 * @param candidates    该型号当前版本等于发布单来源版本的候选设备数
 * @param compatible    其中通过固件兼容矩阵的设备数
 * @param incompatible  其中被矩阵拦截的设备数（candidates - compatible）
 */
public record ModelCompatSummary(String hardwareModel, int candidates, int compatible, int incompatible) {
}
