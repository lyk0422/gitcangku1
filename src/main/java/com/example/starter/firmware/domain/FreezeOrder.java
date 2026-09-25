package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 固件发布冻结令。窗口为 UTC 左闭右开 [startUtc, endUtc)，范围命中型号或发布单即冻结。
 *
 * @param id              冻结令ID
 * @param version         冻结令版本号，从1开始；修订成功加一
 * @param status          ACTIVE 生效中，REVOKED 已撤销
 * @param startUtc        窗口起点UTC，左闭，ISO-8601
 * @param endUtc          窗口终点UTC，右开，严格晚于起点
 * @param models          硬件型号范围，去重按字典序排序
 * @param releaseIds      发布单范围，去重升序
 * @param enforcedVersion 已执行冻结扫荡的版本；null 表示当前版本尚未扫荡
 * @param revokedAtUtc    撤销时刻UTC，未撤销为 null
 */
public record FreezeOrder(long id, int version, FreezeStatus status, String startUtc, String endUtc,
                          List<String> models, List<Long> releaseIds, Integer enforcedVersion,
                          String revokedAtUtc) {

    /**
     * 给定时刻是否落在冻结窗口内（左闭右开）；已撤销的冻结令不再生效。
     */
    public boolean activeAt(java.time.Instant now) {
        if (status != FreezeStatus.ACTIVE) {
            return false;
        }
        java.time.Instant start = java.time.Instant.parse(startUtc);
        java.time.Instant end = java.time.Instant.parse(endUtc);
        return !now.isBefore(start) && now.isBefore(end);
    }

    /**
     * 是否命中型号或发布单范围。
     */
    public boolean hits(String model, Long releaseId) {
        return (model != null && models.contains(model))
                || (releaseId != null && releaseIds.contains(releaseId));
    }
}
