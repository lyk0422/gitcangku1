package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批量离线提交中的单个观测提交项。
 *
 * @param submissionId  提交项唯一标识（客户端生成，全局唯一，用于明细查询与人工裁决）
 * @param observationId 目标观测记录标识
 * @param siteId        采集站点标识（围栏按站点匹配）
 * @param baseVersion   离线修改所基于的基线版本号
 * @param location      候选观测地点完整值
 * @param reading       候选观测读数完整值，十进制字符串，最多三位小数
 * @param note          候选观测备注完整值
 * @param latitude      采集纬度（度，WGS84，[-90,90]）
 * @param longitude     采集经度（度，WGS84，[-180,180]）
 * @param capturedAtUtc 采集时刻（UTC），围栏按此时刻匹配
 */
public record SubmissionItemRequest(
        @NotBlank @Size(max = 128) String submissionId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String siteId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        @NotNull Instant capturedAtUtc) {
}
