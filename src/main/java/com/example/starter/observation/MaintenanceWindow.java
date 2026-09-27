package com.example.starter.observation;

import java.time.Duration;
import java.time.Instant;

/**
 * 维护窗口版本行：对应 maintenance_window 表一行，只追加、永不更新或删除。
 *
 * @param windowId           维护窗口唯一标识（登记窗口与更正窗口各自独立）
 * @param stationId          所属观测站标识
 * @param version            站内维护版本号，从 1 开始单调递增
 * @param kind               行类型：REGISTER 登记 / CORRECTION 更正
 * @param startAtUtc         窗口开始时刻（UTC，左闭）；撤回类更正为 null
 * @param endAtUtc           窗口结束时刻（UTC，右开）；撤回类更正为 null
 * @param supersedesWindowId 本更正替换/撤回的既有窗口标识；登记窗口为 null
 * @param retracted          是否为撤回类更正：true 时不带时间区间，仅撤回既有窗口
 * @param createdAtUtc       本行提交完成时刻（UTC，先后裁决依据）
 */
public record MaintenanceWindow(
        String windowId,
        String stationId,
        int version,
        MaintenanceWindowKind kind,
        Instant startAtUtc,
        Instant endAtUtc,
        String supersedesWindowId,
        boolean retracted,
        Instant createdAtUtc) {

    /**
     * 窗口实际时长（秒，左闭右开 end - start 的实际值）；撤回类更正无区间，返回 null。
     */
    public Long durationSeconds() {
        if (startAtUtc == null || endAtUtc == null) {
            return null;
        }
        return Duration.between(startAtUtc, endAtUtc).getSeconds();
    }

    /**
     * 判断给定 UTC 时刻是否命中窗口：左闭右开 start &lt;= t &lt; end。
     */
    public boolean contains(Instant instant) {
        return startAtUtc != null && endAtUtc != null
                && !instant.isBefore(startAtUtc) && instant.isBefore(endAtUtc);
    }
}
