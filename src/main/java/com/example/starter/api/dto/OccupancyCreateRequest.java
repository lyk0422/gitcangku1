package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建占用请求：依据一条未失效（非 STALE）且结论为 CLEAR 的审查，
 * 占用固化该审查时的航线/空域/气象版本。
 *
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param operator  操作者标识，计入幂等指纹
 * @param reviewId  所依据的审查记录标识
 */
public record OccupancyCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String reviewId) {
}