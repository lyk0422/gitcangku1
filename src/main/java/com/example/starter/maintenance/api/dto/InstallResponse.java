package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 安装部件响应。
 *
 * @param installId                装拆记录标识
 * @param componentId              部件合成编号
 * @param equipmentId              安装所在设备标识
 * @param installReadingId         安装引用的读数标识
 * @param installSampledAt         安装读数的 UTC 采样时刻
 * @param installCumulativeMinutes 安装读数的累计工时快照（分钟），区间起始累计
 * @param componentVersion         操作后的部件版本号
 */
public record InstallResponse(
        long installId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        long componentVersion) {
}
