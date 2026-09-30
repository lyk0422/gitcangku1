package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建活动请求。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param activityId 活动编号，全局唯一
 */
public record CreateActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String activityId
) {
}
