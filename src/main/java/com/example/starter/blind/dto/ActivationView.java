package com.example.starter.blind.dto;

import java.util.List;

/**
 * 启用申请视图：状态与冻结的区组名额快照；不含处理代码或席位号。
 *
 * @param activationId  启用申请编号
 * @param experimentId  实验编号
 * @param siteId        中心编号
 * @param configVersion 申请启用的配置版本号
 * @param status        PENDING_CONFIRMATION / ACTIVE
 * @param leadActor     提交申请的中心负责人编号
 * @param reviewerActor 独立复核确认人编号；未复核为 null
 * @param blocks        申请时冻结的区组名额快照，顺序按区组编号排序
 * @param createdAt     申请（负责人确认）时间，Unix 毫秒，UTC
 * @param confirmedAt   复核确认并启用的时间，Unix 毫秒，UTC；未复核为 null
 */
public record ActivationView(
        String activationId,
        String experimentId,
        String siteId,
        int configVersion,
        String status,
        String leadActor,
        String reviewerActor,
        List<BlockQuotaView> blocks,
        long createdAt,
        Long confirmedAt
) {
}
