package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 紧急例外确认人登记请求。
 */
public record RegisterConfirmerRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String name) {

    public record ConfirmerView(String name) {
    }
}
