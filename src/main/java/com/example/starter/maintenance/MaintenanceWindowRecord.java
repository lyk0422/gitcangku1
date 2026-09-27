package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 观测站维护窗口记录：对应 maintenance_window 表的一行。UTC 左闭右开区间，历史窗口永不物理删除。
 *
 * @param windowId         维护窗口唯一标识
 * @param stationId        所属观测站标识
 * @param startUtc         维护开始时刻（UTC，区间左闭）
 * @param endUtc           维护结束时刻（UTC，区间右开）
 * @param windowVersion    维护版本号；更正窗口必须大于被更正窗口的版本
 * @param status           窗口状态：ACTIVE 生效中 / SUPERSEDED 已被更正取代 / WITHDRAWN 已撤回
 * @param correctsWindowId 更正目标窗口标识；普通登记窗口为 null
 * @param requestId        登记或更正该窗口的请求标识
 * @param createdAtUtc     窗口登记时刻（UTC，服务端时钟）
 */
public record MaintenanceWindowRecord(
        String windowId,
        String stationId,
        Instant startUtc,
        Instant endUtc,
        int windowVersion,
        String status,
        String correctsWindowId,
        String requestId,
        Instant createdAtUtc) {

    /** 窗口状态：生效中。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 窗口状态：已被更正窗口取代。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    /** 窗口状态：已撤回。 */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
}
