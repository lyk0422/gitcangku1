package com.example.starter.evidence.dto;

import com.example.starter.evidence.HoldLevel;
import com.example.starter.evidence.HoldStatus;

import java.time.LocalDateTime;

/**
 * 保留冻结视图。effective 为查询时刻按 UTC 窗口左闭右开派生：
 * status=ACTIVE 且当前 UTC 时刻早于 expiresAt 才为 true；恰好到期即为 false，行不被改写。
 *
 * @param holdRequestId    冻结申请业务键（幂等键）
 * @param level            冻结级别：CASE / EVIDENCE
 * @param caseKey          案件键
 * @param scopeKey         冻结对象键（案件键或证物业务键）
 * @param evidenceKey      证物级冻结的证物键；案件级为 null
 * @param expiresAt        UTC 到期时刻（窗口右端，不含）
 * @param reason           创建原因
 * @param createdBy        创建保管人
 * @param status           行状态：ACTIVE 未解除 / RELEASED 已解除
 * @param effective        查询时刻是否仍有效并阻断销毁
 * @param createdAt        创建时间（Asia/Shanghai）
 * @param releasedBy       解除保管人；null 表示未解除
 * @param releaseReason    解除原因；null 表示未解除
 * @param releaseRequestId 解除申请业务键；null 表示未解除
 * @param releasedAt       UTC 解除时刻；null 表示未解除
 */
public record HoldView(
        String holdRequestId,
        HoldLevel level,
        String caseKey,
        String scopeKey,
        String evidenceKey,
        LocalDateTime expiresAt,
        String reason,
        String createdBy,
        HoldStatus status,
        boolean effective,
        LocalDateTime createdAt,
        String releasedBy,
        String releaseReason,
        String releaseRequestId,
        LocalDateTime releasedAt) {
}
