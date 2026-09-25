package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 坐标观测提交请求：携带经纬度、基准版本、采集时刻与字段内容；设备标识取自路径。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param latitude     原始纬度（度，[-90, 90]）
 * @param longitude    原始经度（度，[-180, 180]）
 * @param frameVersion 提交时使用的坐标基准版本（必须已登记）
 * @param capturedAt   采集时刻（ISO-8601，UTC 或带偏移量）
 * @param location     观测地点（字段内容）
 * @param reading      观测读数，十进制字符串，最多三位小数（字段内容）
 * @param note         观测备注（字段内容）
 */
public record SubmitGeoObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotBlank @Size(max = 64) String frameVersion,
        @NotBlank @Size(max = 64) String capturedAt,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
