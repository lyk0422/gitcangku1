package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离锁定点当前状态。两个维护角色（ISOLATOR、VERIFIER）各确认一次后视为确认完成；
 * 解除时逐点解除。
 *
 * @param orderId                所属隔离令标识
 * @param ordinal                创建请求中的提交序号（从 0 开始）
 * @param lockPointId            锁定点标识，令内唯一
 * @param description            锁定点描述
 * @param confirmedIsolatorBy    ISOLATOR 角色确认操作人；未确认 null
 * @param confirmedIsolatorAt    ISOLATOR 确认时刻（UTC）；未确认 null
 * @param confirmedVerifierBy    VERIFIER 角色确认操作人；未确认 null
 * @param confirmedVerifierAt    VERIFIER 确认时刻（UTC）；未确认 null
 * @param releasedBy             解除操作人；未解除 null
 * @param releasedAt             解除时刻（UTC）；未解除 null
 */
public record LockPoint(
        long orderId,
        int ordinal,
        String lockPointId,
        String description,
        String confirmedIsolatorBy,
        Instant confirmedIsolatorAt,
        String confirmedVerifierBy,
        Instant confirmedVerifierAt,
        String releasedBy,
        Instant releasedAt) {

    public static final String ROLE_ISOLATOR = "ISOLATOR";
    public static final String ROLE_VERIFIER = "VERIFIER";

    public boolean confirmedBy(String role) {
        return ROLE_ISOLATOR.equals(role) ? confirmedIsolatorBy != null : confirmedVerifierBy != null;
    }

    public boolean fullyConfirmed() {
        return confirmedIsolatorBy != null && confirmedVerifierBy != null;
    }

    public boolean released() {
        return releasedBy != null;
    }
}
