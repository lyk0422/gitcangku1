package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 登记区段封锁切换单请求。requestKey 为幂等键；封锁窗口 [startUtc, endUtc) 为左闭右开 UTC。
 */
public record SwitchRegisterRequest(
        @NotBlank String requestKey,
        @NotBlank String switchKey,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {
}
