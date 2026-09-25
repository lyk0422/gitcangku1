package com.example.starter.firmware.domain;

/**
 * 设备拉取被兼容矩阵拦截的记录，同设备同发布单只记首次。
 *
 * @param id              不兼容记录ID
 * @param releaseId       拦截时所在发布单ID
 * @param deviceId        被拦截设备ID
 * @param hardwareModel   被拦截设备的硬件型号
 * @param firmwareVersion 目标固件版本
 * @param matrixVersion   拦截时的兼容矩阵版本
 * @param blockedAtUtc    首次拦截时刻，UTC，ISO-8601 格式
 */
public record IncompatibleRecord(long id, long releaseId, String deviceId, String hardwareModel,
                                 String firmwareVersion, int matrixVersion, String blockedAtUtc) {
}
