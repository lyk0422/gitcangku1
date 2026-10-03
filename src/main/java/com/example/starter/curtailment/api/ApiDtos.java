package com.example.starter.curtailment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 限发接口请求/响应报文。时间字段均为 UTC ISO-8601 瞬时；功率单位 kW，电量单位 kWh。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 创建限发指令请求。instructionNo 缺省时由服务端生成。 */
    public record CreateInstructionRequest(@NotBlank String unitId,
                                           @NotNull Instant startUtc,
                                           @NotNull Instant endUtc,
                                           @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxPowerKw,
                                           String instructionNo) {
    }

    /** 确认请求，requestId 用于幂等。 */
    public record ConfirmRequest(@NotBlank String requestId) {
    }

    /** 撤销请求，requestId 用于幂等。 */
    public record RevokeRequest(@NotBlank String requestId) {
    }

    /** 采样上报/更正请求：同一刻度再次上报（不同 requestId）即迟到更正。 */
    public record SampleRequest(@NotBlank String requestId,
                                @NotNull Instant tickStart,
                                @NotNull @DecimalMin("0") BigDecimal avgPowerKw) {
    }

    /** 对账请求：窗口为 windowStart 起连续 4 个 15 分钟刻度。 */
    public record ReconcileRequest(@NotBlank String requestId,
                                   @NotNull Instant windowStart) {
    }

    /** 指令视图，status 为含派生状态的有效状态（PENDING/CONFIRMED/REVOKED/ENDED）。 */
    public record InstructionView(long id, String instructionNo, String unitId, Instant startUtc, Instant endUtc,
                                  BigDecimal maxPowerKw, String status, int version) {
    }

    /** 采样视图。 */
    public record SampleView(long id, long instructionId, Instant tickStart, BigDecimal avgPowerKw,
                             int sampleVersion) {
    }

    /** 对账刻度视图。 */
    public record TickView(Instant tickStart, BigDecimal avgPowerKw, int sampleVersion, BigDecimal energyKwh) {
    }

    /** 对账单视图，含固化的指令版本与四个刻度。 */
    public record ReconciliationView(long id, long instructionId, Instant windowStart, int instructionVersion,
                                     BigDecimal totalEnergyKwh, List<TickView> ticks) {
    }

    /** 差额记录视图。 */
    public record AdjustmentView(long id, long reconciliationId, Instant tickStart, BigDecimal oldAvgPowerKw,
                                 BigDecimal newAvgPowerKw, BigDecimal deltaEnergyKwh, int sampleVersion,
                                 Instant createdAt) {
    }

    /** 指令详情：指令版本、全部采样刻度版本、对账单与差额链。 */
    public record InstructionDetailView(InstructionView instruction, List<SampleView> samples,
                                        List<ReconciliationView> reconciliations, List<AdjustmentView> adjustments) {
    }

    /** 问题刻度：对账被拒时返回，reason 如 MISSING_SAMPLE / EXCEEDS_CAP / NOT_ALIGNED / NOT_CONFIRMED。 */
    public record ProblemTick(Instant tickStart, String reason, String detail) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(String code, String message, List<ProblemTick> problems) {
    }
}