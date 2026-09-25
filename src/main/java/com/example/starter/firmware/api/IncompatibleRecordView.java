package com.example.starter.firmware.api;

/**
 * 设备拉取不兼容记录视图。
 *
 * @param id             记录ID
 * @param deviceId       被拦截设备ID
 * @param hardwareModel  拦截时设备硬件型号
 * @param releaseId      目标发布单ID
 * @param toVersion      目标固件版本
 * @param matrixVersion  拦截时依据的兼容矩阵版本
 */
public record IncompatibleRecordView(long id, String deviceId, String hardwareModel, long releaseId,
                                     String toVersion, int matrixVersion) {
}
