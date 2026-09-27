package com.example.starter.consent.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 驻留区域登记请求：区域代码必须先登记，授权 epoch 才能绑定该区域。
 *
 * @param requestId  幂等请求标识
 * @param regionCode 区域代码，大写字母开头的大写字母/数字/下划线组合
 * @param regionName 区域可读名称（合成字符串）
 */
public record RegionRegisterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z0-9_]*",
                message = "区域代码必须为大写字母开头的大写字母/数字/下划线组合") String regionCode,
        @NotBlank @Size(max = 128) String regionName) {

    /**
     * 登记成功响应。
     *
     * @param regionCode   区域代码
     * @param regionName   区域可读名称
     * @param registeredAt 登记时间（UTC）
     */
    public record Response(String regionCode, String regionName, Instant registeredAt) {
    }
}
