package com.example.starter.observation.geofence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 批量提交中的单条离线观测：包含采集坐标与采集时刻（UTC）。
 *
 * @param observationId 客户端观测唯一标识（全局唯一）
 * @param siteId        采集站点标识
 * @param latitude      采集纬度，[-90, 90]，最多 7 位小数
 * @param longitude     采集经度，[-180, 180]，最多 7 位小数
 * @param observedAtUtc 采集时刻（UTC，ISO-8601），围栏按此时刻匹配
 * @param payload       观测内容原文；可为 null
 */
public record ObservationItemRequest(
        @NotBlank @Size(max = 128) String observationId,
        @NotBlank @Size(max = 64) String siteId,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        @NotBlank @Size(max = 64) String observedAtUtc,
        @Size(max = 1024) String payload) {
}
