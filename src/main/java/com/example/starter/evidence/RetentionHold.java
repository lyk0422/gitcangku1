package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 保全冻结实体，对应 retention_hold 表。
 * 创建后 caseKey/区间/reason 不可变；解除只更新 version/status/releasedBy/releasedAt。
 *
 * @param id          主键
 * @param holdId      冻结业务键，全局唯一
 * @param holdKey     冻结指纹（案件号+规范化证物+区间+原因+版本），用于幂等重放识别
 * @param caseKey     冻结关联案件号
 * @param effectiveAt UTC 生效时刻（含）
 * @param expireAt    UTC 失效时刻（不含）
 * @param reason      冻结原因
 * @param version     冻结版本，创建为 1，每次解除递增
 * @param status      冻结状态
 * @param createdBy   创建请求方，仅其本人可解除
 * @param createdAt   创建时间（UTC）
 * @param releasedBy  解除操作人；{@code null} 表示未解除
 * @param releasedAt  解除时刻（UTC）；{@code null} 表示未解除
 */
public record RetentionHold(
        Long id,
        String holdId,
        String holdKey,
        String caseKey,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt,
        String reason,
        int version,
        HoldStatus status,
        String createdBy,
        LocalDateTime createdAt,
        String releasedBy,
        LocalDateTime releasedAt) {

    /**
     * 该冻结在指定 UTC 时刻是否“有效”：未解除且处于左闭右开区间内。
     */
    public boolean activeAt(LocalDateTime nowUtc) {
        return status == HoldStatus.ACTIVE
                && !nowUtc.isBefore(effectiveAt)
                && nowUtc.isBefore(expireAt);
    }
}
