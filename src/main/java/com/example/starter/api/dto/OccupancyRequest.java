package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建空域占用请求：必须引用仍为当前结论的 CLEAR 审核，
 * 占用固化审核时的气象版本；审核已 STALE 返回 409。
 *
 * @param reviewId  来源审核记录标识
 * @param operator  操作者标识，参与幂等指纹计算
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyRequest(
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}