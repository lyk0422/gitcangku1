package com.example.starter.bridge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 进桥确认、出桥释放、撤销共用的操作请求；requestId 为该操作的幂等键。
 */
public record PermitOperationRequest(
        /** 操作幂等请求键。 */
        @NotBlank @Size(max = 64) String requestId) {
}