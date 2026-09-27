package com.example.starter.blind.dto;

import java.util.List;

/**
 * 退役批次明细视图：在摘要基础上附带封存序号明细。
 * 封存的都是未分配席位；该视图仅对执行退役的 COORDINATOR 角色开放。
 *
 * @param retirementId 退役批次编号
 * @param experimentId 所属实验编号
 * @param reason       退役理由（不可变）
 * @param actorId      执行退役的操作者编号
 * @param createdAt    退役时间，Unix 毫秒，UTC
 * @param codes        本批次退役的代码及配额快照，按处理代码升序
 * @param sealedSeats  封存序号明细，按处理代码、区组号、席位号升序
 */
public record RetirementDetailView(
        String retirementId,
        String experimentId,
        String reason,
        String actorId,
        long createdAt,
        List<RetiredCodeView> codes,
        List<SealedSeatView> sealedSeats
) {
}
