package com.example.starter.maintenance;

import java.time.Duration;
import java.time.Instant;

/**
 * 维护窗口响应：数量或时长校验失败时由错误响应给出实际值、要求值与差额，正常响应携带窗口完整属性。
 *
 * @param windowId           维护窗口唯一标识
 * @param stationId          所属观测站标识
 * @param maintenanceVersion 站点内单调递增的维护版本号
 * @param startAtUtc         窗口开始时刻（UTC，含）
 * @param endAtUtc           窗口结束时刻（UTC，不含）
 * @param durationSeconds    窗口时长（秒），左闭右开区间 end - start，必然为正
 * @param windowType         REGISTER / CORRECTION
 * @param correctsWindowId   更正的原窗口标识；初始登记为 null
 * @param status             ACTIVE / DELETED
 * @param createdAtUtc       窗口登记完成时刻（UTC）
 */
public record MaintenanceWindowResponse(
        String windowId,
        String stationId,
        int maintenanceVersion,
        Instant startAtUtc,
        Instant endAtUtc,
        long durationSeconds,
        String windowType,
        String correctsWindowId,
        String status,
        Instant createdAtUtc) {

    /**
     * 由领域对象构造响应，时长按实际区间差计算。
     */
    public static MaintenanceWindowResponse of(MaintenanceWindow window) {
        return new MaintenanceWindowResponse(
                window.windowId(),
                window.stationId(),
                window.maintenanceVersion(),
                window.startAtUtc(),
                window.endAtUtc(),
                Duration.between(window.startAtUtc(), window.endAtUtc()).getSeconds(),
                window.windowType().name(),
                window.correctsWindowId(),
                window.status().name(),
                window.createdAtUtc());
    }
}
