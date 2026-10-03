package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * API 请求 DTO 集合。所有时刻字段均为 UTC（ISO-8601，如 2026-10-05T09:59:00Z）。
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 登记花序请求。windowHours 缺省 24。 */
    public record CreateInflorescenceRequest(
            @NotBlank String code,
            @NotNull Instant firstOpenAt,
            @Positive Integer windowHours) {
    }

    /** 摘除花序请求。 */
    public record RemoveInflorescenceRequest(@NotNull Instant removedAt) {
    }

    /** 新建花粉批号请求。 */
    public record CreatePollenBatchRequest(@NotBlank String batchNo) {
    }

    /** 登记授粉操作请求。 */
    public record PollinateRequest(
            @NotBlank String requestId,
            @NotBlank String pollenBatchNo,
            @NotBlank String technician,
            @NotNull Instant executedAt) {
    }

    /** 撤销授粉操作请求。 */
    public record RevokeRequest(@NotNull Instant revokedAt) {
    }

    /** 登记结果确认请求。result 取值 SUCCESS / FAILED。 */
    public record ConfirmRequest(
            @NotBlank String requestId,
            @NotBlank String result,
            @NotBlank String technician,
            @NotNull Instant confirmedAt) {
    }
}