package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销乘务资格请求。requestKey 为幂等键；撤销与发布经全局发布锁串行化。
 */
public record QualificationRevokeRequest(
        @NotBlank String requestKey,
        @NotBlank String memberCode) {
}