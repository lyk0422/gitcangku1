package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 值班换班请求：推进值班版本，此后旧审核结果的时效诊断标记值班维度 STALE。
 *
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record DutyAdvanceRequest(
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
