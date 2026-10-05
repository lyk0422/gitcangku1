package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 部件装拆账一行：一次安装及其拆卸（未拆卸时拆卸字段为 null）。
 * 区间为左闭右开 [installSampledAt, removeSampledAt)，相邻接续（拆卸时刻即下次安装时刻）合法。
 *
 * @param installationId            装拆记录自增主键
 * @param componentId               部件合成编号
 * @param equipmentId               本次安装所在设备标识
 * @param installReadingId          安装引用的设备累计读数标识
 * @param installSampledAt          安装时刻（安装读数的 UTC 采样时刻快照）
 * @param installCumulativeMinutes  安装时设备累计工时快照（分钟）
 * @param removeReadingId           拆卸引用的读数标识；未拆卸为 null
 * @param removeSampledAt           拆卸时刻快照；未拆卸为 null
 * @param removeCumulativeMinutes   拆卸时设备累计工时快照（分钟）；未拆卸为 null
 */
public record Installation(
        long installationId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String removeReadingId,
        Instant removeSampledAt,
        Long removeCumulativeMinutes) {

    /** 是否仍安装在设备上（未拆卸）。 */
    public boolean active() {
        return removeReadingId == null;
    }
}
