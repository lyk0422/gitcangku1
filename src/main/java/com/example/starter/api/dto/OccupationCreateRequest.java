package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建占用请求。仅允许引用未失效（非 STALE）的 CLEAR 审核结果；
 * 占用固化审核时的航线、空域与气象版本。
 *
 * @param reviewId  所依据的审核记录标识
 * @param routeId   占用航线标识（须与审核记录一致）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupationCreateRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
