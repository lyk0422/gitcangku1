package com.example.starter.blackstart;

import java.time.Instant;

/**
 * 黑启动计划。
 * 状态机：IN_PROGRESS（执行中） -&gt; STARTED（第三步成功，已启动）或 TERMINATED（值班员终止）。
 * 进入终态后记录只读；currentStep 为当前待执行步骤号（1~3），固定三步不允许跳步。
 */
public record BlackStartPlan(
        long id,
        String status,
        int currentStep,
        Instant createdAt,
        Instant completedAt,
        Instant terminatedAt,
        String terminationReason) {

    /** 执行中。 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 已启动（终态）。 */
    public static final String STATUS_STARTED = "STARTED";
    /** 已终止（终态）。 */
    public static final String STATUS_TERMINATED = "TERMINATED";

    /** 计划固定步数：电池母线带电、逆变器同步、关键负载接入。 */
    public static final int TOTAL_STEPS = 3;
    /** 第三步关键负载接入功率上限，单位 kW。 */
    public static final double MAX_LOAD_POWER_KW = 200.0;
    /** 母线电压合格下限，单位 V。 */
    public static final double MIN_VOLTAGE_V = 380.0;
    /** 母线电压合格上限，单位 V。 */
    public static final double MAX_VOLTAGE_V = 420.0;
    /** 频率合格下限，单位 Hz。 */
    public static final double MIN_FREQUENCY_HZ = 49.5;
    /** 频率合格上限，单位 Hz。 */
    public static final double MAX_FREQUENCY_HZ = 50.5;
}