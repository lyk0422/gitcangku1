package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 区域登记请求：区域代码必须预先登记后才能用于授权代次绑定与写入路由。
 *
 * @param requestId  幂等请求标识
 * @param regionCode 区域代码（合成字符串），全局唯一
 */
public record RegionRegisterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 32) String regionCode) {
}
