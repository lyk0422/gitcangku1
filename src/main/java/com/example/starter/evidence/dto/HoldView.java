package com.example.starter.evidence.dto;

import com.example.starter.evidence.HoldScope;
import com.example.starter.evidence.HoldStatus;

import java.time.LocalDateTime;

/**
 * 保留冻结视图，展示两级冻结的当前有效状态、到期时刻与解除链。
 *
 * @param id             冻结记录主键，解除时按 id 指定目标
 * @param requestId     冻结请求幂等键
 * @param scope         冻结层级：CASE 案件级 / EVIDENCE 证物级
 * @param caseKey       案件键
 * @param evidenceKey   证物键；案件级为 null
 * @param expiresUtc    UTC 到期时刻（不含）
 * @param reason        冻结原因
 * @param createdBy     创建保管人
 * @param createdUtc    生效时刻（UTC）
 * @param status        存储状态：ACTIVE 未解除 / RELEASED 已解除
 * @param effective     查询时刻是否仍有效：未解除且当前 UTC 时刻早于 expiresUtc（左闭右开）
 * @param releasedBy    解除保管人；null 未解除
 * @param releaseReason 解除原因；null 未解除
 * @param releasedUtc   解除时刻（UTC）；null 未解除
 */
public record HoldView(
        Long id,
        String requestId,
        HoldScope scope,
        String caseKey,
        String evidenceKey,
        LocalDateTime expiresUtc,
        String reason,
        String createdBy,
        LocalDateTime createdUtc,
        HoldStatus status,
        boolean effective,
        String releasedBy,
        String releaseReason,
        LocalDateTime releasedUtc) {
}
