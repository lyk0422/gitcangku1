package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记线路区段档案请求。designSpeedKmh 为线路既有设计速度（10～300 km/h），
 * minHeadwayMinutes 为线路既有最小间隔（1～240 分钟），作为整体顺延步进单位。
 */
public record SectionProfileRequest(
        @NotBlank String sectionId,
        @NotNull @Min(10) @Max(300) Integer designSpeedKmh,
        @NotNull @Min(1) @Max(240) Integer minHeadwayMinutes) {
}
