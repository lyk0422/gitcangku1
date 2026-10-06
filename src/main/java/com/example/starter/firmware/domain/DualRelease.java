package com.example.starter.firmware.domain;

/**
 * 双分区试启动发布单。
 *
 * @param id              发布单ID
 * @param version         发布单版本号，从1开始，暂停成功加一
 * @param model           目标设备型号
 * @param targetVersion   目标固件版本，健康确认通过后切换
 * @param status          状态：ACTIVE 投放中，PAUSED 已暂停
 * @param committedCount  首次进入 COMMITTED 的任务数，每任务只计一次
 * @param rolledBackCount 首次进入 ROLLED_BACK 的任务数（含失败与到期），每任务只计一次
 */
public record DualRelease(long id, int version, String model, String targetVersion,
                          DualReleaseStatus status, int committedCount, int rolledBackCount) {
}
