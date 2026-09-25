package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 航班批量审查请求。先计算全部航班的最终容量与关闭影响，任一拒绝整批不批准。
 *
 * @param flightIds 待审查航班标识列表（至少 1 个，至多 200 个）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record FlightBatchReviewRequest(
        @NotEmpty @Size(max = 200) List<@Size(max = 64) String> flightIds,
        @NotBlank @Size(max = 64) String requestId) {
}
