package com.example.starter.blind.dto;

import java.util.List;

/**
 * 退役批次摘要视图：不含封存席位明细，历史列表与明细查询共用。
 *
 * @param retirementId 退役批次编号
 * @param experimentId 所属实验编号
 * @param reason       退役理由（不可变）
 * @param actorId      执行退役的操作者编号
 * @param createdAt    退役时间，Unix 毫秒，UTC
 * @param codes        本批次退役的代码及配额快照，按处理代码升序
 */
public record RetirementView(
        String retirementId,
        String experimentId,
        String reason,
        String actorId,
        long createdAt,
        List<RetiredCodeView> codes
) {
}
