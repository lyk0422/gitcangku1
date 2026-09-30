package com.example.starter.film.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 整片审核请求；审核要求该影片所有计划片段均已有有效提交。
 *
 * @param requestId 客户端幂等请求号
 */
public record ReviewRequest(@NotBlank String requestId) {
}
