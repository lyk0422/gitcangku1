package com.example.starter.firmware.dto;

/**
 * 发布单信息响应。
 *
 * @param id          发布单主键
 * @param model       目标设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本
 * @param ratio       当前投放比例，0~100
 * @param version     乐观锁版本，从 1 开始
 * @param status      状态：ACTIVE 或 CANCELLED
 */
public record ReleaseResponse(long id, String model, String fromVersion, String toVersion,
                              int ratio, int version, String status) {
}
