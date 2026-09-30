package com.example.starter.evidence.dto;

import com.example.starter.evidence.HoldScope;
import com.example.starter.evidence.HoldState;

import java.time.LocalDateTime;

/**
 * 保留冻结视图：展示冻结级别、UTC 窗口、查询时刻有效状态与解除链。
 * 状态 ACTIVE/RELEASED/EXPIRED 为查询时按 UTC 时钟即时计算，不落库、不回改历史。
 *
 * @param holdKey       冻结业务键
 * @param scope         冻结级别：CASE 案件级 / EVIDENCE 证物级
 * @param caseKey       所属案件键
 * @param evidenceKey   证物业务键；NULL 表示案件级冻结
 * @param createdBy     创建人
 * @param reason        创建原因
 * @param expireAt      UTC 到期时刻（窗口右端，开区间）
 * @param state         查询时刻状态：ACTIVE 生效中 / RELEASED 已解除 / EXPIRED 已到期
 * @param active        查询时刻是否阻断销毁（state=ACTIVE）
 * @param releasedBy    解除人（不同于创建人的保管人）；NULL 表示未解除
 * @param releaseReason 解除签明原因；NULL 表示未解除
 * @param releasedAt    UTC 解除时刻；NULL 表示未解除
 * @param createdAt     UTC 窗口起点（创建生效时刻，闭区间起点）
 */
public record HoldView(
        String holdKey,
        HoldScope scope,
        String caseKey,
        String evidenceKey,
        String createdBy,
        String reason,
        LocalDateTime expireAt,
        HoldState state,
        boolean active,
        String releasedBy,
        String releaseReason,
        LocalDateTime releasedAt,
        LocalDateTime createdAt) {
}
