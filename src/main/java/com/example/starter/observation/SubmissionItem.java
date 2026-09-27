package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批量离线观测提交的单条条目：携带采集坐标、采集时刻与三个可编辑字段的完整候选值。
 *
 * @param submissionId   提交记录唯一标识
 * @param siteId         站点标识
 * @param observationId  目标观测记录标识
 * @param baseVersion    离线修改所基于的基线版本号
 * @param location       观测地点候选完整值
 * @param reading        观测读数候选完整值，十进制字符串，最多三位小数
 * @param note           观测备注候选完整值
 * @param latitude       采集纬度，范围 [-90,90]
 * @param longitude      采集经度，范围 [-180,180]
 * @param collectedAtUtc 采集时刻（UTC），服务端据此匹配围栏
 */
public record SubmissionItem(
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
        @NotNull Instant collectedAtUtc) {
}
