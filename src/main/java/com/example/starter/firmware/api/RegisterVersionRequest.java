package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 固件版本登记请求：predecessor 为直接前置版本，可为空（链起点）；
 * 已登记版本再次登记视为修改前置版本，同样校验前置存在且不形成环。
 */
public record RegisterVersionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String version,
        @Size(max = 64) String predecessor) {
}
