package com.example.starter.observation;

import java.time.Instant;

/**
 * 维护窗口版本行响应：登记窗口与追加更正窗口共用。
 *
 * @param windowId           维护窗口标识
 * @param stationId          所属观测站标识
 * @param version            站内维护版本号
 * @param kind               REGISTER / CORRECTION
 * @param startAtUtc         窗口开始时刻（UTC，左闭）；撤回行省略
 * @param endAtUtc           窗口结束时刻（UTC，右开）；撤回行省略
 * @param durationSeconds    窗口实际时长（秒，实际值 end-start）；撤回行为 null
 * @param supersedesWindowId 被替换/撤回的既有窗口标识；登记窗口省略
 * @param retracted          是否撤回行
 * @param createdAtUtc       本行提交完成时刻（UTC）
 */
public record MaintenanceWindowResponse(
        String windowId,
        String stationId,
        int version,
        String kind,
        Instant startAtUtc,
        Instant endAtUtc,
        Long durationSeconds,
        String supersedesWindowId,
        boolean retracted,
        Instant createdAtUtc) {

    public static MaintenanceWindowResponse of(MaintenanceWindow window) {
        return new MaintenanceWindowResponse(
                window.windowId(), window.stationId(), window.version(), window.kind().name(),
                window.startAtUtc(), window.endAtUtc(), window.durationSeconds(),
                window.supersedesWindowId(), window.retracted(), window.createdAtUtc());
    }
}
