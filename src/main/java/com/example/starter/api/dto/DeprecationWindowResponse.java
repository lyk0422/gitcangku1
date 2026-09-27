package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 弃用窗口视图（含已撤销的历史记录）。
 *
 * @param effectiveAt        弃用生效时刻（UTC），[登记, 生效) 左闭右开区间内仅告警
 * @param replacementVersion 同制品替代版本号
 * @param reason             弃用说明，null 表示未填写
 * @param revoked            是否已撤销：true=已撤销（记录保留）
 * @param revokedAt          撤销时刻（UTC），未撤销为 null
 */
public record DeprecationWindowResponse(
        long id,
        String name,
        int version,
        Instant effectiveAt,
        int replacementVersion,
        String reason,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
