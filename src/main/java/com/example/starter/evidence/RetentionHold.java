package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保全冻结实体，对应 retention_hold 表。
 * 生效区间为 UTC 左闭右开 [effectiveFrom, effectiveTo)；evidenceKeys 为规范化（去重、排序）集合。
 *
 * @param id            主键
 * @param holdKey       冻结业务键（兼幂等命令键），全局唯一
 * @param caseKey       案件号
 * @param evidenceKeys  规范化证物键集合（去重、字典序排序）
 * @param effectiveFrom 生效起点（UTC，左闭）
 * @param effectiveTo   生效终点（UTC，右开）
 * @param reason        冻结原因
 * @param version       冻结版本：创建为 1，解除时校验并递增
 * @param status        冻结状态
 * @param createdBy     创建操作人
 * @param createdAt     创建时间（Asia/Shanghai）
 * @param releasedBy    解除操作人；null 表示未解除
 * @param releasedAt    解除时间（Asia/Shanghai）；null 表示未解除
 */
public record RetentionHold(
        Long id,
        String holdKey,
        String caseKey,
        List<String> evidenceKeys,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        String reason,
        int version,
        HoldStatus status,
        String createdBy,
        LocalDateTime createdAt,
        String releasedBy,
        LocalDateTime releasedAt) {
}
