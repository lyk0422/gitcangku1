package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批次诊断响应：批次明细、全部离群记录及复核、统计重算历史与放行快照。
 * 读取本身不改变任何状态。
 *
 * @param batch            批次明细
 * @param outliers         全部离群记录（按检测顺序）
 * @param statsHistory     统计重算历史（按发生顺序）
 * @param releaseSnapshots 放行快照历史（按发生顺序）
 */
public record DiagnosticsResponse(
        BatchResponse batch,
        List<OutlierResponse> outliers,
        List<StatsRecordResponse> statsHistory,
        List<ReleaseSnapshotResponse> releaseSnapshots) {
}