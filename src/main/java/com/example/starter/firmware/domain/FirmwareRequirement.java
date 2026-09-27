package com.example.starter.firmware.domain;

/**
 * 固件直接声明的模块需求（兼容矩阵的一行）。
 *
 * @param model           设备型号
 * @param firmwareVersion 固件版本，点分数字
 * @param moduleName      所需模块名
 * @param minVersion      最低模块版本，点分数字
 * @param updatedAtUtc    最近登记时刻，UTC，ISO-8601
 */
public record FirmwareRequirement(String model, String firmwareVersion, String moduleName,
                                  String minVersion, String updatedAtUtc) {
}
