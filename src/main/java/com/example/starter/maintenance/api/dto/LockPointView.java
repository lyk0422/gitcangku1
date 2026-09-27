package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 锁定点视图（当前状态与操作人证据）。
 *
 * @param orderId               所属隔离令标识
 * @param ordinal               创建时的提交序号（从 0 开始，稳定排序）
 * @param lockPointId           锁定点标识
 * @param description           锁定点描述
 * @param state                 点状态：PENDING 待确认 / PARTIALLY_CONFIRMED 单角色已确认 /
 *                              CONFIRMED 双角色确认完成 / RELEASED 已解除
 * @param isolatorConfirmedBy   ISOLATOR 确认操作人；未确认 null
 * @param isolatorConfirmedAt   ISOLATOR 确认时刻（UTC）；未确认 null
 * @param verifierConfirmedBy   VERIFIER 确认操作人；未确认 null
 * @param verifierConfirmedAt   VERIFIER 确认时刻（UTC）；未确认 null
 * @param releasedBy            解除操作人；未解除 null
 * @param releasedAt            解除时刻（UTC）；未解除 null
 */
public record LockPointView(
        long orderId,
        int ordinal,
        String lockPointId,
        String description,
        String state,
        String isolatorConfirmedBy,
        Instant isolatorConfirmedAt,
        String verifierConfirmedBy,
        Instant verifierConfirmedAt,
        String releasedBy,
        Instant releasedAt) {
}
