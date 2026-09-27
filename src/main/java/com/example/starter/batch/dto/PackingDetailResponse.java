package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次包装核销明细：计划、活跃封箱、全部封箱历史版本、放行快照与差异诊断。
 *
 * <p>读取接口，不改变任何状态。未登记包装计划时 plan/seals/snapshot/diagnostic 均为 null
 * （null 表示该批次从未登记包装计划）。
 *
 * @param plan         包装计划与当前累计值；未登记时为 null
 * @param activeSeals  当前活跃封箱（占用数量与标签中），按标签号升序
 * @param sealHistory  全部封箱事件（含已作废与重封的历史版本），按自增 id 即提交顺序排列
 * @param snapshot     放行快照；未放行时为 null
 * @param diagnostic   标签核销差异诊断；未登记计划时为 null
 * @param window       可选 UTC 时间窗 [from, to) 统计；未指定窗口时为 null
 */
public record PackingDetailResponse(
        String batchKey,
        PackingPlanResponse plan,
        List<SealEventResponse> activeSeals,
        List<SealEventResponse> sealHistory,
        ReleaseSnapshotResponse snapshot,
        LabelDiagnosticResponse diagnostic,
        SealWindowResponse window
) {
}
