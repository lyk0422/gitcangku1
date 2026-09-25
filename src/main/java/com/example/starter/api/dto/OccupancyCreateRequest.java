package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建高度层占用请求。关联的审核必须为 CLEAR 且未失效（STALE 返回 422）；
 * 高度带时间重叠的 ACTIVE 占用达到容量返回 429。
 *
 * @param reviewId  关联的审核记录标识（结论必须为 CLEAR）
 * @param zoneId    占用区域标识
 * @param bandId    占用高度带标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyCreateRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String zoneId,
        @NotBlank @Size(max = 64) String bandId,
        @NotBlank @Size(max = 64) String requestId) {
}
