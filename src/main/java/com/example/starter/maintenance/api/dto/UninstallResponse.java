package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 拆卸部件响应。
 *
 * @param installId                   装拆记录标识
 * @param componentId                 部件合成编号
 * @param equipmentId                 拆卸所在设备标识
 * @param uninstallReadingId          拆卸引用的读数标识
 * @param uninstallSampledAt          拆卸读数的 UTC 采样时刻
 * @param uninstallCumulativeMinutes  拆卸读数的累计工时快照（分钟），区间结束累计
 * @param durationMinutes             本区间时长（分钟）= 结束累计 - 起始累计
 * @param accumulatedMinutes          拆卸后部件已累计寿命（分钟）
 * @param componentVersion            操作后的部件版本号
 */
public record UninstallResponse(
        long installId,
        String componentId,
        String equipmentId,
        String uninstallReadingId,
        Instant uninstallSampledAt,
        long uninstallCumulativeMinutes,
        long durationMinutes,
        long accumulatedMinutes,
        long componentVersion) {
}
