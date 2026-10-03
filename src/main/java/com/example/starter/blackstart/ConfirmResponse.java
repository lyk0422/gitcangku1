package com.example.starter.blackstart;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 步骤确认响应。拒绝时 HTTP 状态为 422，body 中 outcome 为 REJECTED 并携带拒绝原因。
 *
 * @param planId 计划主键
 * @param stepNo 提交的步骤号
 * @param requestId 客户端请求标识
 * @param outcome 确认结果：SUCCESS / REJECTED
 * @param planStatus 确认后的计划状态
 * @param currentStep 确认后的当前步骤号（拒绝时不推进）
 * @param rejectReason 拒绝原因，成功为 null
 * @param voltage 记录的母线电压读数（V）
 * @param frequency 记录的频率读数（Hz）
 * @param powerKw 记录的负载接入功率读数（kW）
 * @param recordedAt 记录时刻（UTC）
 */
public record ConfirmResponse(
        long planId,
        int stepNo,
        String requestId,
        String outcome,
        String planStatus,
        int currentStep,
        String rejectReason,
        BigDecimal voltage,
        BigDecimal frequency,
        BigDecimal powerKw,
        Instant recordedAt) {
}