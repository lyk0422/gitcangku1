package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建高度层占用请求。仅可对 CLEAR 审查创建；占用的区域与高度带必须与
 * 审查时的垂直分离判定一致（二维相交且巡航高度落入该带）。
 * 容量已满返回 429（不占用 requestId）；关联审查已 STALE 返回 422。
 *
 * @param reviewId  关联的 CLEAR 审查记录标识
 * @param zoneId    占用的区域标识
 * @param bandLower 占用高度带下限（含），米
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyCreateRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Integer bandLower,
        @NotBlank @Size(max = 64) String requestId) {
}
