package com.example.starter.firmware.api;

/**
 * 执行租约视图：领取与续租成功后返回。
 *
 * <p>租约有效期左闭右开：[当前时刻, expiresAtUtc)，到达 {@code expiresAtUtc} 即失效。
 * 幂等重放返回的是该 requestId 首次成功时的租约快照，不代表租约仍然有效。</p>
 *
 * @param taskId       任务ID
 * @param agentId      持有人代理ID
 * @param token        租约令牌，首次领取为1，到期后重新领取加一
 * @param expiresAtUtc 到期时刻，UTC，ISO-8601格式（如2026-10-08T00:00:30Z）
 */
public record LeaseView(long taskId, String agentId, int token, String expiresAtUtc) {
}
