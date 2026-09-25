package com.example.starter.firmware.domain;

/**
 * 固件灰度发布单。
 *
 * @param id          发布单ID
 * @param version     发布单版本号，从1开始，每次扩量或区域上限修改成功加一
 * @param model       目标设备型号
 * @param fromVersion 来源固件版本
 * @param toVersion   目标固件版本，必须与来源版本不同
 * @param ratio       投放比例，取值0~100，只增不减
 * @param regionLimit 各区域同时进行中（已下发未完成）任务数上限，取值1~1000，null表示不限流
 * @param status      状态：ACTIVE 投放中，CANCELLED 已取消
 */
public record ReleaseOrder(long id, int version, String model, String fromVersion, String toVersion,
                           int ratio, Integer regionLimit, ReleaseStatus status) {
}
