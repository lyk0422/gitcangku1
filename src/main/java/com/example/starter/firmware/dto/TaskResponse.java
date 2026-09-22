package com.example.starter.firmware.dto;

/**
 * 投放任务信息响应。
 *
 * @param id          任务 ID
 * @param rolloutId   所属发布单 ID
 * @param deviceId    目标设备 ID
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param status      状态：PENDING / SUCCESS / FAILED / CANCELLED
 */
public record TaskResponse(long id, long rolloutId, String deviceId,
                           String fromVersion, String toVersion, String status) {
}
