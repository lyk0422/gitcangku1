package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 部件状态查询响应：当前归属设备、累计寿命与历次装拆账。
 *
 * @param componentId         部件合成编号
 * @param lifetimeMinutes     寿命上限（分钟）
 * @param accumulatedMinutes  已累计寿命（分钟）
 * @param remainingMinutes    剩余寿命（分钟），下限 0
 * @param currentEquipmentId  当前在装设备标识；未在装时为 null
 * @param currentInstallId    当前在装记录标识；未在装时为 null
 * @param version             部件版本号
 * @param installs            历次装拆账（按安装先后升序）
 */
public record ComponentStatusResponse(
        String componentId,
        long lifetimeMinutes,
        long accumulatedMinutes,
        long remainingMinutes,
        String currentEquipmentId,
        Long currentInstallId,
        long version,
        List<InstallView> installs) {
}
