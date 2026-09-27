package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 维护窗口响应：明细与历史查询共用；sealed 表示窗口已被合并快照覆盖（不可修改/撤回，只能追加更正）。
 *
 * @param windowId         维护窗口唯一标识
 * @param stationId        所属观测站标识
 * @param startUtc         维护开始时刻（UTC，区间左闭）
 * @param endUtc           维护结束时刻（UTC，区间右开）
 * @param windowVersion    维护版本号
 * @param status           窗口状态：ACTIVE / SUPERSEDED / WITHDRAWN
 * @param correctsWindowId 更正目标窗口标识；普通登记窗口不返回
 * @param sealed           是否已被合并快照覆盖
 * @param createdAtUtc     窗口登记时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowResponse(
        String windowId,
        String stationId,
        Instant startUtc,
        Instant endUtc,
        int windowVersion,
        String status,
        String correctsWindowId,
        boolean sealed,
        Instant createdAtUtc) {

    public static WindowResponse of(MaintenanceWindowRecord record, boolean sealed) {
        return new WindowResponse(record.windowId(), record.stationId(), record.startUtc(), record.endUtc(),
                record.windowVersion(), record.status(), record.correctsWindowId(), sealed, record.createdAtUtc());
    }
}
