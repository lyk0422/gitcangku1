package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 装拆账历史条目（查询视图）。
 *
 * @param installId                   装拆记录标识
 * @param equipmentId                 安装所在设备标识
 * @param installReadingId            安装引用的读数标识
 * @param installSampledAt            安装读数的 UTC 采样时刻
 * @param installCumulativeMinutes    区间起始累计（分钟）
 * @param uninstallReadingId          拆卸引用的读数标识；在装时为 null
 * @param uninstallSampledAt          拆卸读数的 UTC 采样时刻；在装时为 null
 * @param uninstallCumulativeMinutes  区间结束累计（分钟）；在装时为 null
 * @param durationMinutes             本区间时长（分钟）；在装时为 null
 * @param open                        是否在装
 */
public record InstallView(
        long installId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String uninstallReadingId,
        Instant uninstallSampledAt,
        Long uninstallCumulativeMinutes,
        Long durationMinutes,
        boolean open) {
}
