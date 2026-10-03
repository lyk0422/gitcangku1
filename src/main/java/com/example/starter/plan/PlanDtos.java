package com.example.starter.plan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 接口请求与视图对象；时刻字段均为 UTC Instant。
 */
public final class PlanDtos {

    private PlanDtos() {
    }

    /** 创建计划请求：固定区域边界与检查点清单。 */
    public record CreatePlanRequest(@NotBlank String planCode,
                                    @NotBlank String areaBoundary,
                                    @NotEmpty List<@NotBlank String> checkpoints) {
    }

    /** 检查点确认请求：headcount 必须为 0，confirmedAt 缺省取当前时刻。 */
    public record ConfirmRequest(@NotBlank String confirmer,
                                 @NotNull @Min(0) Integer headcount,
                                 Instant confirmedAt) {
    }

    /** 人员进入事件请求：occurredAt 缺省取当前时刻。 */
    public record PersonEntryRequest(@NotBlank String reporter,
                                     Instant occurredAt) {
    }

    /** 签发指令请求：requestId 为幂等键。 */
    public record IssueRequest(@NotBlank String requestId,
                               @NotBlank String issuedBy) {
    }

    /** 执行回报请求：requestId 为幂等键。 */
    public record ExecuteRequest(@NotBlank String requestId,
                                 @NotBlank String executor) {
    }

    /** 单条检查点确认视图。 */
    public record ConfirmationView(long roundNo, String checkpointCode, String confirmer,
                                   int headcount, Instant confirmedAt) {
    }

    /** 检查轮次视图，含本轮全部确认。 */
    public record RoundView(long roundNo, String status, Instant openedAt, Instant closedAt,
                            List<ConfirmationView> confirmations) {
    }

    /** 指令视图，含有效期与状态。 */
    public record CommandView(long commandId, String requestId, String issuedBy,
                              Instant issuedAt, Instant expiresAt, String status, Instant executedAt) {
    }

    /** 执行回报视图。 */
    public record ReportView(long reportId, long commandId, String requestId, String executor,
                             String result, Instant reportedAt) {
    }

    /** 人员进入事件视图。 */
    public record PersonEntryView(long eventId, String reporter, Instant occurredAt, Instant recordedAt) {
    }

    /** 计划完整状态视图：轮次、各点确认时刻、指令有效期与最终状态。 */
    public record PlanView(long planId, String planCode, String areaBoundary, String status,
                           boolean occupied, Instant createdAt, List<String> checkpoints,
                           List<RoundView> rounds, List<CommandView> commands,
                           List<ReportView> executionReports, List<PersonEntryView> personEntries) {
    }

    /** 统一错误响应体。 */
    public record ErrorView(String error) {
    }
}