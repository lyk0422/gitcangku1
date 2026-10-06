package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 组激活请求：全部当前组代次成员准备齐备后才允许提交；
 * expectedVersion 为期望的组发布单版本号。
 */
public record ActivateGroupRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedVersion) {
}
