package com.example.starter.blackstart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 计划查询视图：当前步骤、每步读数、拒绝理由和终止或完成时刻。
 *
 * @param planId 计划主键
 * @param name 计划名称
 * @param status 计划状态：IN_PROGRESS / STARTED / TERMINATED
 * @param currentStep 当前待执行步骤号
 * @param createdAt 创建时刻（UTC）
 * @param completedAt 完成时刻（UTC），未完成为 null
 * @param terminatedAt 终止时刻（UTC），未终止为 null
 * @param terminationReason 终止原因，未终止为 null
 * @param steps 全部确认记录（含被拒绝的历史），按提交顺序
 */
public record PlanView(
        long planId,
        String name,
        String status,
        int currentStep,
        Instant createdAt,
        Instant completedAt,
        Instant terminatedAt,
        String terminationReason,
        List<StepRecord> steps) {

    /**
     * 单条步骤确认记录视图。
     *
     * @param stepNo 步骤号
     * @param requestId 客户端请求标识
     * @param outcome 确认结果：SUCCESS / REJECTED
     * @param voltage 母线电压读数（V）
     * @param frequency 频率读数（Hz）
     * @param powerKw 负载接入功率读数（kW）
     * @param deviceStatus 设备状态
     * @param rejectReason 拒绝原因，成功为 null
     * @param recordedAt 记录时刻（UTC）
     */
    public record StepRecord(
            int stepNo,
            String requestId,
            String outcome,
            BigDecimal voltage,
            BigDecimal frequency,
            BigDecimal powerKw,
            String deviceStatus,
            String rejectReason,
            Instant recordedAt) {
    }
}