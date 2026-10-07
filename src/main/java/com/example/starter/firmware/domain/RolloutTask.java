package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * <p>执行租约：代理领取后持有 {@code leaseAgentId}/{@code leaseToken}，有效期至 {@code leaseExpiresAt}
 * （UTC，左闭右开）。有效期内不能被其他代理抢占；到期后任意代理可领取递增后的新令牌。
 * 任务首次终结（SUCCESS/FAILED）时冻结实际写回结果的代理与令牌，此后旧租约的领取/续租重放
 * 只返回其首次领取时的租约快照，不再恢复其有效性。</p>
 *
 * @param id            任务ID
 * @param releaseId     所属发布单ID
 * @param deviceId      设备ID
 * @param status        任务状态
 * @param firstResult   首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param leaseAgentId  当前执行租约持有人代理ID；从未领取为 null
 * @param leaseToken    当前执行租约令牌，首次领取为 1，到期重领加一；未领取为 null
 * @param leaseExpiresAt 当前执行租约到期时刻（UTC）；到达该时刻即失效，未领取为 null
 * @param resultAgentId 首次终结实际写回结果的代理ID，终结后冻结，未终结为 null
 * @param resultToken   首次终结实际写回结果时使用的租约令牌，终结后冻结，未终结为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String leaseAgentId, Integer leaseToken,
                          Instant leaseExpiresAt, String resultAgentId, Integer resultToken) {
}
