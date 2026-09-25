package com.example.starter.evidence.dto;

import com.example.starter.evidence.HoldStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保全冻结视图。
 *
 * @param holdKey       冻结业务键
 * @param caseKey       案件号
 * @param evidenceKeys  规范化证物键集合
 * @param effectiveFrom 生效起点（UTC，左闭）
 * @param effectiveTo   生效终点（UTC，右开）
 * @param reason        冻结原因
 * @param version       冻结版本
 * @param status        冻结状态
 * @param effective     查询时刻是否有效（ACTIVE 且区间覆盖当前 UTC 时刻）
 * @param createdBy     创建操作人
 * @param createdAt     创建时间（Asia/Shanghai）
 * @param releasedBy    解除操作人；null 表示未解除
 * @param releasedAt    解除时间（Asia/Shanghai）；null 表示未解除
 */
public record HoldView(
        String holdKey,
        String caseKey,
        List<String> evidenceKeys,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        String reason,
        int version,
        HoldStatus status,
        boolean effective,
        String createdBy,
        LocalDateTime createdAt,
        String releasedBy,
        LocalDateTime releasedAt) {
}
