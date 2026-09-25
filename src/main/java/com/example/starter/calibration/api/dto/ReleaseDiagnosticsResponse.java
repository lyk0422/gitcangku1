package com.example.starter.calibration.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 放行诊断：对一批测量按补偿门禁预评估，不产生状态变更。
 *
 * @param keys             参与诊断的测量标识（按请求顺序）
 * @param uncertaintyLimit 批次不确定度上限；未提供为 null
 * @param pass             整批是否满足放行条件
 * @param items            逐项诊断（按测量标识字典序稳定输出）
 */
public record ReleaseDiagnosticsResponse(
        List<String> keys,
        String uncertaintyLimit,
        boolean pass,
        List<ItemDiagnostic> items) {

    /**
     * 单项放行诊断。
     *
     * @param key               测量标识
     * @param found             测量是否存在
     * @param status            当前状态
     * @param hasEnvironment    是否记录了环境（温湿度齐全）
     * @param compensatedPassed 补偿后值是否合格；未补偿为 null
     * @param uncertainty       测量不确定度；未提供为 null
     * @param reasons           不放行原因码
     */
    public record ItemDiagnostic(
            String key,
            boolean found,
            String status,
            boolean hasEnvironment,
            Boolean compensatedPassed,
            BigDecimal uncertainty,
            List<String> reasons) {
    }
}
