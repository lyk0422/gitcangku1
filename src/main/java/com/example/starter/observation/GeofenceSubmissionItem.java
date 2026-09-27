package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批量离线观测提交的单条明细：携带采集坐标、采集时刻与三方合并候选值。
 * 经纬度范围由服务端校验（422），任一明细不合法则整批回滚。
 *
 * @param submissionId   全局唯一提交标识
 * @param siteId         站点标识
 * @param observationId  目标观测记录标识
 * @param baseVersion    离线修改所基于的基线版本号
 * @param location       观测地点候选值
 * @param reading        观测读数候选值，十进制字符串，最多三位小数
 * @param note           观测备注候选值
 * @param latitude       采集纬度，[-90,90]
 * @param longitude      采集经度，[-180,180]
 * @param observedAtUtc  采集时刻（UTC），ISO-8601 如 2026-09-27T10:00:00Z
 */
public record GeofenceSubmissionItem(
        @NotBlank @Size(max = 128) String submissionId,
        @NotBlank @Size(max = 64) String siteId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        @NotNull Instant observedAtUtc) {
}
