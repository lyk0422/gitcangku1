package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 装拆记录视图（安装/拆卸写操作响应）。拆卸字段在未拆卸时为 null。
 *
 * @param installationId            装拆记录主键
 * @param componentId               部件合成编号
 * @param equipmentId               本次安装所在设备标识
 * @param installReadingId          安装引用的读数标识
 * @param installSampledAt          安装时刻（UTC，取自安装读数采样时刻）
 * @param installCumulativeMinutes  安装时设备累计工时快照（分钟）
 * @param removeReadingId           拆卸引用的读数标识；未拆卸为 null
 * @param removeSampledAt           拆卸时刻（UTC）；未拆卸为 null
 * @param removeCumulativeMinutes   拆卸时设备累计工时快照（分钟）；未拆卸为 null
 * @param deltaMinutes              本次装拆区间计入寿命的分钟数（拆卸 - 安装）；未拆卸为 null
 * @param usedMinutes               操作后部件累计寿命（分钟）
 * @param active                    是否仍安装在设备上
 * @param componentVersion          操作后的部件版本号
 */
public record InstallationResponse(
        long installationId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String removeReadingId,
        Instant removeSampledAt,
        Long removeCumulativeMinutes,
        Long deltaMinutes,
        long usedMinutes,
        boolean active,
        long componentVersion) {
}
