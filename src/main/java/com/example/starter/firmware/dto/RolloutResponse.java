package com.example.starter.firmware.dto;

/**
 * 发布单信息响应。
 *
 * @param id          发布单 ID
 * @param model       目标设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param ratio       当前投放比例
 * @param status      状态：ACTIVE 或 CANCELLED
 * @param version     发布单版本号，从 1 开始，每次成功修改加一
 */
public record RolloutResponse(long id, String model, String fromVersion, String toVersion,
                              int ratio, String status, int version) {
}
