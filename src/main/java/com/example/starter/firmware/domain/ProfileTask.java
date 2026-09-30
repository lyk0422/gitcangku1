package com.example.starter.firmware.domain;

/**
 * 画像投放任务，仅为兼容设备创建；同设备同投放单最多一条。
 *
 * @param id             任务ID
 * @param launchId       所属投放单ID
 * @param deviceId       设备ID
 * @param profileVersion 筛选时冻结的画像版本，供失败率审计，下发后不可改
 * @param status         任务状态：PENDING 待回执，SUCCESS 成功，FAILED 失败
 * @param firstResult    首次回执结果（SUCCESS/FAILED），未回执为 null
 */
public record ProfileTask(long id, long launchId, String deviceId, int profileVersion,
                          TaskStatus status, ReceiptResult firstResult) {
}
