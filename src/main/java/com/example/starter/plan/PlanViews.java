package com.example.starter.plan;

import java.time.Instant;
import java.util.List;

/**
 * 查询与命令响应视图。所有时刻均为 UTC。
 */
public final class PlanViews {

    private PlanViews() {
    }

    /** 计划概要状态。 */
    public record PlanStateView(long planId, String name, String boundary, String status,
            boolean occupied, int currentRound) {
    }

    /** 单条检查点确认记录。 */
    public record ConfirmationView(long confirmationId, String checkpointCode, int round,
            String confirmedBy, int headcount, Instant confirmedAt) {
    }

    /** 人员进入事件结果。 */
    public record EntryView(long entryId, long planId, int newRound, boolean occupied,
            Instant detectedAt) {
    }

    /** 爆破指令视图，status 为生效状态（含按当前时刻推算的 EXPIRED）。 */
    public record CommandView(long commandId, long planId, int round, String requestId,
            Instant issuedAt, Instant expiresAt, String status) {
    }

    /** 执行回报视图。 */
    public record ExecutionView(long reportId, long commandId, long planId, String result,
            Instant reportedAt, String planStatus) {
    }

    /** 检查点及其历史确认。 */
    public record CheckpointState(String code, String description,
            List<ConfirmationView> confirmations) {
    }

    /** 计划完整查询视图：检查轮次、各点确认时刻、指令有效期与最终状态。 */
    public record PlanDetail(long planId, String name, String boundary, String status,
            boolean occupied, int currentRound, List<CheckpointState> checkpoints,
            List<CommandView> commands, List<EntryView> entries,
            List<ExecutionView> executions) {
    }

    /** 签发结果：view 为指令视图，replay 为 true 表示幂等重放。 */
    public record IssueOutcome(CommandView view, boolean replay) {
    }

    /** 执行回报结果：view 为回报视图，replay 为 true 表示幂等重放。 */
    public record ExecutionOutcome(ExecutionView view, boolean replay) {
    }
}