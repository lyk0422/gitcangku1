package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签核销差异诊断：只读推导，不改变任何状态。
 *
 * <p>已放行批次若发现标签差异，不允许通过作废改写快照，只能按既有召回流程处置；
 * 本诊断持续保留实际值、要求值与差额，作为召回依据。
 *
 * @param reconciled             是否核销一致：活跃封箱数量之和等于计划数量，且活跃封箱数等于已用标签数
 * @param plannedQuantity        计划数量（要求值）
 * @param sealedQuantity         活跃封箱数量之和（实际值）
 * @param quantityDifference     数量差额 = sealedQuantity - plannedQuantity；0 表示刚好完成
 * @param activeSealCount        活跃封箱条数（每个 sealKey 仅最新版本且状态 SEALED 计入）
 * @param usedLabelCount         已用（活跃占用）标签数
 * @param labelSealDifference    标签数与封箱数差额 = usedLabelCount - activeSealCount；一致时为 0
 * @param labelsOutsideRange     超出登记号段的活跃标签号（升序）；正常约束下恒为空，异常时定位越界标签
 * @param snapshotConsistent     放行快照与当前推导值是否一致；未放行时为 null（null 表示尚无快照）
 * @param missingAgainstSnapshot 相比放行快照缺失的标签号（升序）；未放行时为 null
 * @param extraAgainstSnapshot   相比放行快照多出的标签号（升序）；未放行时为 null
 */
public record LabelDiagnosticResponse(
        boolean reconciled,
        long plannedQuantity,
        long sealedQuantity,
        long quantityDifference,
        long activeSealCount,
        long usedLabelCount,
        long labelSealDifference,
        List<Long> labelsOutsideRange,
        Boolean snapshotConsistent,
        List<Long> missingAgainstSnapshot,
        List<Long> extraAgainstSnapshot
) {
}
