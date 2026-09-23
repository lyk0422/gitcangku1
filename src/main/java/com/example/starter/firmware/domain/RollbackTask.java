package com.example.starter.firmware.domain;

/**
 * 回退波次任务，同（计划,跳,轮,设备）最多一条。同一设备仅在上一跳 SUCCESS 后进入下一跳；
 * 人工恢复只针对当前跳未成功设备生成新 round 任务。
 *
 * @param id               任务ID
 * @param planId           所属计划ID
 * @param hopIndex         跳号，从0开始
 * @param round            波次轮次，首跳首轮为1，每次人工恢复加一
 * @param deviceId         设备ID
 * @param expectedVersion  派发时冻结的设备应处版本，SUCCESS 回执据此做条件版本切换
 * @param toVersion        派发时冻结的该跳目标版本
 * @param sourceReleaseId  派发时冻结的来源正向投放发布单ID
 * @param status           任务状态：PENDING/SUCCESS/FAILED/CANCELLED
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param receiptKey       终结本任务的回执业务键，全局唯一，未回执为 null
 */
public record RollbackTask(long id, long planId, int hopIndex, int round, String deviceId,
                           String expectedVersion, String toVersion, long sourceReleaseId,
                           TaskStatus status, ReceiptResult firstResult, String receiptKey) {
}
