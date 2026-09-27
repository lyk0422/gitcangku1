package com.example.starter.blind.dto;

import java.util.List;

/**
 * 盲态代码退役批次视图（不可变事件快照）。
 * 仅包含处理代码级与区组级数量，不关联任何参与者，普通盲态纪律不变。
 *
 * @param retirementId  退役批次编号
 * @param experimentId  实验编号
 * @param reason        退役原因，不可变
 * @param operatorActor 执行退役的操作者编号，不可变
 * @param status        批次状态，固定 COMMITTED（退役不可撤销，无中间态）
 * @param createdAt     批次业务时间，Unix 毫秒，UTC
 * @param committedAt   事务提交时间，Unix 毫秒，UTC
 * @param codes         各处理代码配额明细，按处理代码升序
 * @param blocks        各区组配额重算快照，按区组号升序
 */
public record RetirementView(
        String retirementId,
        String experimentId,
        String reason,
        String operatorActor,
        String status,
        long createdAt,
        long committedAt,
        List<RetiredCodeView> codes,
        List<BlockQuotaView> blocks
) {
}
