package com.example.starter.firmware.domain;

/**
 * 固件灰度发布单。
 *
 * @param id           发布单ID
 * @param version      发布单版本号，从1开始，每次扩量成功加一
 * @param model        目标设备型号
 * @param fromVersion  来源固件版本
 * @param toVersion    目标固件版本，必须与来源版本不同
 * @param ratio        当前生效投放比例，取值0~100；分级发布单等于当前解锁级别比例，只增不减
 * @param status       状态：ACTIVE 投放中，PAUSED 失败自动暂停，COMPLETED 已完成，CANCELLED 已取消
 * @param currentLevel 当前已解锁最高金丝雀级别，从1开始；未配置分级时恒为1
 */
public record ReleaseOrder(long id, int version, String model, String fromVersion, String toVersion,
                           int ratio, ReleaseStatus status, int currentLevel) {
}
