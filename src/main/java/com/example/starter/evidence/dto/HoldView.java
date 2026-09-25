package com.example.starter.evidence.dto;

import com.example.starter.evidence.HoldStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保全冻结视图。evidenceKeys 为规范化（字典序、去重）后的集合快照。
 *
 * @param holdId      冻结业务键
 * @param caseKey     案件号
 * @param reason      冻结原因
 * @param effectiveAt UTC 生效时刻（含）
 * @param expireAt    UTC 失效时刻（不含）
 * @param version     当前版本
 * @param status      冻结状态
 * @param createdBy   创建请求方
 * @param createdAt   创建时间（UTC）
 * @param releasedBy  解除操作人；{@code null} 表示未解除
 * @param releasedAt  解除时刻（UTC）；{@code null} 表示未解除
 * @param evidenceKeys 规范化证物集合
 * @param effectiveNow 在查询时刻是否为有效冻结
 */
public record HoldView(
        String holdId,
        String caseKey,
        String reason,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt,
        int version,
        HoldStatus status,
        String createdBy,
        LocalDateTime createdAt,
        String releasedBy,
        LocalDateTime releasedAt,
        List<String> evidenceKeys,
        boolean effectiveNow) {
}
