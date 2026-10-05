package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 部件寿命台账查询响应：当前所在设备、历次装拆与累计寿命。
 *
 * @param componentId         部件合成编号
 * @param lifetimeMinutes     寿命上限（分钟）
 * @param usedMinutes         已累计寿命（分钟）
 * @param remainingMinutes    剩余寿命（分钟），寿命上限 - 已累计
 * @param exhausted           是否已达寿命上限（达到后禁止再安装，已安装者仍可拆卸）
 * @param currentEquipmentId  当前所在设备标识；未安装为 null
 * @param version             部件当前版本号
 * @param installations       历次装拆记录（按安装时刻升序）
 */
public record ComponentStatusResponse(
        String componentId,
        long lifetimeMinutes,
        long usedMinutes,
        long remainingMinutes,
        boolean exhausted,
        String currentEquipmentId,
        long version,
        List<InstallationView> installations) {

    /**
     * 单次装拆区间视图，左闭右开 [installSampledAt, removeSampledAt)。
     *
     * @param installationId            装拆记录主键
     * @param equipmentId               所在设备标识
     * @param installReadingId          安装引用的读数标识
     * @param installSampledAt          安装时刻（UTC）
     * @param installCumulativeMinutes  安装时设备累计工时（分钟）
     * @param removeReadingId           拆卸引用的读数标识；未拆卸为 null
     * @param removeSampledAt           拆卸时刻（UTC）；未拆卸为 null
     * @param removeCumulativeMinutes   拆卸时设备累计工时（分钟）；未拆卸为 null
     * @param active                    是否当前安装区间
     */
    public record InstallationView(
            long installationId,
            String equipmentId,
            String installReadingId,
            Instant installSampledAt,
            long installCumulativeMinutes,
            String removeReadingId,
            Instant removeSampledAt,
            Long removeCumulativeMinutes,
            boolean active) {
    }
}
