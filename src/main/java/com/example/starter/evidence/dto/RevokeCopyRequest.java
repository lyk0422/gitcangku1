package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回收阅检副本请求。仅当前保管人可回收；回收不删除签发历史。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 期望的证物版本号；null 表示不校验，提供时与当前版本不一致返回 409
 */
public record RevokeCopyRequest(
        @NotBlank @Size(max = 64) String requestId,
        Long expectedVersion) {
}