package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 漂移修正激活结果：读数新版本与保养项目整体重算在一个事务内原子提交，只产生一个快照版本。
 *
 * @param correctionKey                修正单业务唯一键
 * @param equipmentId                  所属设备标识
 * @param equipmentVersion             激活后的设备版本（仅加一）
 * @param maintenanceSnapshotVersion   本次产生的唯一保养快照版本
 * @param intervalStart                首锚点采样时刻（UTC）
 * @param intervalEnd                  尾锚点采样时刻（UTC）
 * @param readings                     区间内全部读数的修正结果（按采样时刻升序）
 * @param maintenanceItems             全部保养项目重算后的 DUE/NOT_DUE 与下一阈值
 * @param activatedAt                  激活时刻（UTC）
 */
public record DriftActivateResponse(
        String correctionKey,
        String equipmentId,
        long equipmentVersion,
        long maintenanceSnapshotVersion,
        Instant intervalStart,
        Instant intervalEnd,
        List<DriftReadingView> readings,
        List<DriftMaintenanceItemView> maintenanceItems,
        Instant activatedAt) {
}
