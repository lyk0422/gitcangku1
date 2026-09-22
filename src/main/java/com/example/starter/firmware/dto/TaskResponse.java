package com.example.starter.firmware.dto;

/**
 * 投放任务信息响应。
 *
 * @param id          任务主键
 * @param releaseId   所属发布单主键
 * @param deviceId    目标设备标识
 * @param model       设备型号快照
 * @param fromVersion 来源固件版本快照
 * @param toVersion   目标固件版本快照
 * @param status      任务状态：PENDING、SUCCESS、FAILED 或 CANCELLED
 */
public record TaskResponse(long id, long releaseId, String deviceId, String model,
                           String fromVersion, String toVersion, String status) {
}
