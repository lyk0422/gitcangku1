package com.example.starter.firmware.domain;

/**
 * 固件模块需求矩阵头（每个目标固件版本一行）。
 *
 * @param firmwareVersion 目标固件版本
 * @param matrixVersion   矩阵版本号，从 1 开始，每次成功修订加一
 * @param updatedAtUtc    最近修订时刻，UTC，ISO-8601
 */
public record FirmwareMatrix(String firmwareVersion, int matrixVersion, String updatedAtUtc) {
}
