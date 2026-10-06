package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id                   任务ID
 * @param releaseId            所属发布单ID
 * @param deviceId             设备ID
 * @param status               任务状态
 * @param firstResult          首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param targetSecurityIndex  任务创建时冻结的目标安全索引，此后不随发布单或设备底线变化
 * @param receiptSecurityIndex 首次回执携带的证明索引（安装证据），未回执或未提供为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer targetSecurityIndex,
                          Integer receiptSecurityIndex) {
}
