package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备本地维护更新请求：更新当前版本与当前镜像摘要并使镜像代次加一。
 * 型号与灰度分桶号登记后不可修改，故本请求不接受这两个字段。
 * expectedImageGeneration 为期望镜像代次，与设备当前代次不符返回 409，保证维护与拉取、回执串行。
 */
public record MaintenanceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String newVersion,
        @NotBlank @Size(max = 64) String newDigest,
        @NotNull @Min(1) Integer expectedImageGeneration) {
}