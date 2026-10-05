package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务执行日志实体，对应 task_execution_logs 表。
 * 只增不改：授权到期后已执行日志保留；按 id 升序即发生顺序。
 * action 取值 START/CONTINUE/COMPLETE；note 仅 CONTINUE 必填，其余可为 null。
 * occurredAt 为 UTC，微秒精度。
 */
public record TaskExecutionLog(
        long id,
        long taskId,
        long incidentId,
        String action,
        String note,
        String actor,
        Instant occurredAt) {

    /** 执行动作：开始。 */
    public static final String ACTION_START = "START";

    /** 执行动作：继续执行。 */
    public static final String ACTION_CONTINUE = "CONTINUE";

    /** 执行动作：完成。 */
    public static final String ACTION_COMPLETE = "COMPLETE";
}
