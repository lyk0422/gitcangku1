package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * 限发接口请求 DTO 集合；时间字段一律为 UTC 的 ISO-8601  instant。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 创建限发指令请求。
     *
     * @param unitId      目标机组编码
     * @param startUtc    限发区间起点（含），UTC
     * @param endUtc      限发区间终点（不含），UTC，左闭右开
     * @param maxPowerKw  区间内允许的最大平均功率，单位 kW
     */
    public record CreateDirectiveRequest(
            @NotBlank String unitId,
            @NotNull Instant startUtc,
            @NotNull Instant endUtc,
            @Positive int maxPowerKw) {
    }

    /**
     * 确认指令请求；requestId 幂等，同参重放返回原结果，异参返回 409。
     *
     * @param requestId   幂等请求标识
     * @param confirmedBy 确认人
     */
    public record ConfirmRequest(
            @NotBlank String requestId,
            @NotBlank String confirmedBy) {
    }

    /**
     * 上报 15 分钟平均功率采样；同一刻度重复上报视为迟到更正，追加新版本。
     *
     * @param tickUtc     采样刻度起点，UTC，须落在指令区间内并按 15 分钟对齐
     * @param avgPowerKw  该刻度内平均功率，单位 kW
     */
    public record ReadingRequest(
            @NotNull Instant tickUtc,
            @PositiveOrZero int avgPowerKw) {
    }

    /**
     * 对账请求；requestId 幂等，同参重放返回原结果，异参返回 409。
     *
     * @param requestId 幂等请求标识
     * @param auditor   对账人
     */
    public record ReconcileRequest(
            @NotBlank String requestId,
            @NotBlank String auditor) {
    }
}