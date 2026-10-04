package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 部件装拆账视图：区间左闭右开 [installSampledAt, removeSampledAt)。
 *
 * @param installId                 装拆记录主键
 * @param componentId               部件合成编号
 * @param equipmentId               安装所在设备标识
 * @param installReadingId          安装引用的读数标识
 * @param installSampledAt          安装读数 UTC 采样时刻（快照）
 * @param installCumulativeMinutes  安装时设备累计工时快照（分钟）
 * @param removeReadingId           拆卸引用的读数标识；未拆卸为 null
 * @param removeSampledAt           拆卸读数 UTC 采样时刻（快照）；未拆卸为 null
 * @param removeCumulativeMinutes   拆卸时设备累计工时快照（分钟）；未拆卸为 null
 * @param segmentMinutes            本区间结算分钟（拆卸累计-安装累计）；未拆卸为 null
 * @param open                      是否仍在装
 * @param componentVersion          操作后的部件版本号（仅写操作响应有意义，查询时为 0）
 */
public record ComponentInstallView(
        long installId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String removeReadingId,
        Instant removeSampledAt,
        Long removeCumulativeMinutes,
        Long segmentMinutes,
        boolean open,
        long componentVersion) {
}