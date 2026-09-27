package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 区域登记请求：预先登记一个驻留区域代码，登记后不可删除。
 *
 * @param requestId  幂等请求标识
 * @param regionCode 驻留区域代码（合成字符串，如 eu-west、ap-sg）
 */
public record RegionRegisterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String regionCode) {
}
