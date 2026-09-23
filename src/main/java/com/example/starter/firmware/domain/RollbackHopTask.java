package com.example.starter.firmware.domain;

/**
 * 回退计划逐跳派发任务，同设备同跳同一轮次至多一条。
 *
 * @param id              回跳任务ID
 * @param receiptKey      回执凭证，派发时生成，全局唯一；回执必须原样携带
 * @param planId          所属回退计划ID
 * @param deviceId        设备ID
 * @param hopIndex        跳次，从1开始
 * @param roundNo         该任务所属监控轮次
 * @param expectedVersion 本跳期望起始版本快照
 * @param targetVersion   本跳目标版本快照
 * @param sourceReleaseId 本跳逆向对应的原正向投放发布单ID快照
 * @param status          任务状态
 * @param firstResult     首次回执结果（SUCCESS/FAILED），未回执为 null
 */
public record RollbackHopTask(long id, String receiptKey, long planId, String deviceId, int hopIndex,
                              int roundNo, String expectedVersion, String targetVersion,
                              long sourceReleaseId, TaskStatus status, ReceiptResult firstResult) {
}
