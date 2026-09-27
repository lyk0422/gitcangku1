package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批次历史与诊断响应：全部离群记录（含复核与裁决）、统计重算历史与放行快照。
 * 只读查询，不改变任何状态。
 *
 * @param batchKey     批次键
 * @param revision     当前测量修订版本号
 * @param status       当前状态：OPEN / RELEASED
 * @param outliers     全部离群记录（按检测顺序）
 * @param statsRecords 全部统计重算记录（按重算顺序）
 * @param releases     全部放行快照（按放行顺序）
 */
public record BatchHistoryResponse(
        String batchKey,
        int revision,
        String status,
        List<OutlierResponse> outliers,
        List<StatsRecordResponse> statsRecords,
        List<BatchReleaseResponse> releases) {
}
