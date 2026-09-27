package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 维护窗口：站点内按登记顺序单调递增的维护版本，UTC 左闭右开区间。
 *
 * @param windowId           维护窗口唯一标识
 * @param stationId          所属观测站标识
 * @param maintenanceVersion 站点内单调递增的维护版本号，从 1 开始
 * @param startAtUtc         窗口开始时刻（UTC，含）
 * @param endAtUtc           窗口结束时刻（UTC，不含）
 * @param windowType         窗口类型：REGISTER 初始登记 / CORRECTION 事后更正
 * @param correctsWindowId   更正窗口所更正的原窗口标识；初始登记为 null
 * @param status             窗口状态：ACTIVE 生效中 / DELETED 已删除
 * @param createdAtUtc       窗口登记完成时刻（UTC）
 */
public record MaintenanceWindow(
        String windowId,
        String stationId,
        int maintenanceVersion,
        Instant startAtUtc,
        Instant endAtUtc,
        WindowType windowType,
        String correctsWindowId,
        WindowStatus status,
        Instant createdAtUtc) {
}
