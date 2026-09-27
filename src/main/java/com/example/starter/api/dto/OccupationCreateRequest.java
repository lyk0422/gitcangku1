package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建空域占用请求。仅允许引用结论为 CLEAR 且未失效（非 STALE）的审核记录；
 * 占用固化审核时的气象标准/预测/值班版本。
 *
 * @param reviewId  引用的审核记录标识
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupationCreateRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
