package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 部件装拆账记录：一次安装对应一行，拆卸后回填结束快照与区间时长。
 * 区间为左闭右开 [安装读数时刻, 拆卸读数时刻)，时长 = 结束累计 - 起始累计。
 *
 * @param installId                   装拆记录自增主键
 * @param componentId                 部件合成编号
 * @param equipmentId                 安装所在设备标识
 * @param installReadingId            安装引用的读数标识（该设备已提交读数）
 * @param installSampledAt            安装读数的 UTC 采样时刻（快照）
 * @param installCumulativeMinutes    安装读数的累计工时快照（分钟），区间起始累计
 * @param uninstallReadingId          拆卸引用的读数标识；NULL 表示当前在装
 * @param uninstallSampledAt          拆卸读数的 UTC 采样时刻（快照）；在装时为 NULL
 * @param uninstallCumulativeMinutes  拆卸读数的累计工时快照（分钟），区间结束累计；在装时为 NULL
 * @param durationMinutes             本区间时长（分钟）= 结束累计 - 起始累计；在装时为 NULL
 */
public record ComponentInstall(
        long installId,
        String componentId,
        String equipmentId,
        String installReadingId,
        Instant installSampledAt,
        long installCumulativeMinutes,
        String uninstallReadingId,
        Instant uninstallSampledAt,
        Long uninstallCumulativeMinutes,
        Long durationMinutes) {

    /** 是否在装（未拆卸）。 */
    public boolean open() {
        return uninstallReadingId == null;
    }
}
