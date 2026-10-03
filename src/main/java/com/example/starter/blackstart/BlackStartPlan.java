package com.example.starter.blackstart;

import java.time.Instant;

/**
 * 黑启动计划，对应表 black_start_plan。
 *
 * <p>状态机：IN_PROGRESS（执行中） -&gt; STARTED（第三步完成，已启动）或 TERMINATED（值班员终止）。
 * 终态后记录只读，重新启动须创建新计划。</p>
 *
 * @param id 计划主键
 * @param name 计划名称
 * @param status 计划状态：IN_PROGRESS / STARTED / TERMINATED
 * @param currentStep 当前待执行步骤号：1 电池母线带电；2 逆变器同步；3 关键负载接入；已启动时保持为 3
 * @param createdAt 计划创建时刻（UTC）
 * @param completedAt 第三步完成时刻（UTC），未完成为 null
 * @param terminatedAt 值班员终止时刻（UTC），未终止为 null
 * @param terminationReason 终止原因，未终止为 null
 */
public record BlackStartPlan(
        long id,
        String name,
        String status,
        int currentStep,
        Instant createdAt,
        Instant completedAt,
        Instant terminatedAt,
        String terminationReason) {

    /** 计划状态：执行中。 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 计划状态：已启动（第三步完成）。 */
    public static final String STATUS_STARTED = "STARTED";
    /** 计划状态：已终止。 */
    public static final String STATUS_TERMINATED = "TERMINATED";

    /**
     * 是否已终止。
     */
    public boolean isTerminated() {
        return STATUS_TERMINATED.equals(status);
    }

    /**
     * 是否已启动完成。
     */
    public boolean isStarted() {
        return STATUS_STARTED.equals(status);
    }
}