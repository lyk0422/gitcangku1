package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 部件装拆账记录：一段安装区间 [安装读数时刻, 拆卸读数时刻)，左闭右开，相邻接续合法。
 * 安装与拆卸均快照所引用读数的 UTC 采样时刻与累计分钟，被引用读数禁止修订。
 *
 * @param installId                 装拆记录自增主键
 * @param componentId               部件合成编号
 * @param equipmentId               安装所在设备标识
 * @param installReadingId          安装引用的设备读数标识
 * @param installSampledAt          安装读数的 UTC 采样时刻（快照，区间起点，含）
 * @param installCumulativeMinutes  安装时设备累计工时快照（分钟）
 * @param removeReadingId           拆卸引用的设备读数标识；null 表示尚未拆卸
 * @param removeSampledAt           拆卸读数的 UTC 采样时刻（快照，区间终点，不含）；null 表示尚未拆卸
 * @param removeCumulativeMinutes   拆卸时设备累计工时快照（分钟）；null 表示尚未拆卸
 * @param installOperator           安装操作者
 * @param removeOperator            拆卸操作者；null 表示尚未拆卸
 * @param installedAt               安装登记时刻（UTC）
 * @param removedAt                 拆卸登记时刻（UTC）；null 表示尚未拆卸
 */
public record ComponentInstall(
        long installId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String removeReadingId,
        Instant removeSampledAt,
        Long removeCumulativeMinutes,
        String installOperator,
        String removeOperator,
        Instant installedAt,
        Instant removedAt) {

    /** 是否仍在装（未拆卸）。 */
    public boolean open() {
        return removeReadingId == null;
    }

    /** 本区间结算分钟（拆卸累计-安装累计）；未拆卸时为 null。 */
    public Long segmentMinutes() {
        return open() ? null : removeCumulativeMinutes - installCumulativeMinutes;
    }
}