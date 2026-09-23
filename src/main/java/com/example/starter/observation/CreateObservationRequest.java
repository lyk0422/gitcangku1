package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建观测记录请求。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param observationId   观测记录唯一标识
 * @param location        观测地点
 * @param reading         观测读数，十进制字符串，最多三位小数
 * @param note            观测备注
 * @param siteKey         站点标识（重复观测簇归并匹配维度）
 * @param observationType 观测类型（重复观测簇归并匹配维度）
 * @param observedAt      观测发生时刻（ISO-8601，UTC）
 * @param deviceId        采集设备标识，可为空
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotBlank @Size(max = 128) String siteKey,
        @NotBlank @Size(max = 64) String observationType,
        @NotBlank @Size(max = 40) String observedAt,
        @Size(max = 128) String deviceId) {
}
