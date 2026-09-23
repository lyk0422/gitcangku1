package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 组合包借出撤销请求。仅允许尚无任何归还且全部证物仍在借出人名下时原子执行。
 *
 * @param requestId 幂等请求键
 */
public record PackageCancelRequest(
        @NotBlank @Size(max = 64) String requestId) {
}
