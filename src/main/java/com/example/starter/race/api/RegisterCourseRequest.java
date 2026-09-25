package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 登记赛道请求；登记后初始无纪录。
 *
 * @param courseKey 赛道标识，全局唯一
 * @param requestId 全局唯一请求ID（幂等键）
 */
public record RegisterCourseRequest(
        @NotBlank String courseKey,
        @NotBlank String requestId
) {
}
