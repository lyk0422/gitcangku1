package com.example.starter.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * API 请求体定义，带 Bean Validation 约束。
 */
public final class PlanRequests {

    private PlanRequests() {
    }

    /** 创建计划：固定区域边界与检查点清单。 */
    public record CreatePlanRequest(@NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 512) String boundary,
            @NotEmpty List<@Valid CheckpointInput> checkpoints) {
    }

    /** 检查点定义。 */
    public record CheckpointInput(@NotBlank @Size(max = 64) String code,
            @Size(max = 256) String description) {
    }

    /** 检查点确认：headcount 必须为 0（业务层校验）。 */
    public record ConfirmationRequest(@NotBlank String checkpointCode,
            @NotBlank String confirmedBy, @Min(0) int headcount) {
    }

    /** 人员进入事件。 */
    public record EntryRequest(@NotBlank String detectedBy) {
    }

    /** 重新清场（解除有人标记）。 */
    public record ZoneClearRequest(@NotBlank String clearedBy) {
    }

    /** 签发爆破指令。 */
    public record IssueCommandRequest(@NotBlank @Size(max = 64) String requestId,
            @NotBlank String issuedBy) {
    }

    /** 执行回报。 */
    public record ExecutionReportRequest(@NotBlank @Size(max = 64) String requestId,
            @NotBlank String reportedBy, @NotBlank String result) {
    }
}