package com.example.starter.blackstart;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 步骤确认记录，对应表 step_attempt。每次确认提交（成功或被拒绝）都留痕，历史失败不清空。
 *
 * @param id 确认记录主键
 * @param planId 所属计划主键
 * @param stepNo 提交的步骤号：1 电池母线带电；2 逆变器同步；3 关键负载接入
 * @param requestId 客户端请求标识，同一计划内唯一；同参重放返回原结果，异参返回 409
 * @param requestHash 请求参数摘要（SHA-256 十六进制）
 * @param outcome 确认结果：SUCCESS 成功并推进步骤；REJECTED 拒绝且不推进步骤号
 * @param voltage 母线电压读数（V），允许范围 380~420
 * @param frequency 频率读数（Hz），允许范围 49.5~50.5
 * @param powerKw 负载接入功率读数（kW），仅第三步要求，不得超过 200
 * @param deviceStatus 设备状态，仅 NORMAL 可接受
 * @param rejectReason 拒绝原因，成功记录为 null
 * @param recordedAt 记录时刻（UTC）
 */
public record StepAttempt(
        long id,
        long planId,
        int stepNo,
        String requestId,
        String requestHash,
        String outcome,
        BigDecimal voltage,
        BigDecimal frequency,
        BigDecimal powerKw,
        String deviceStatus,
        String rejectReason,
        Instant recordedAt) {

    /** 确认结果：成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 确认结果：拒绝。 */
    public static final String OUTCOME_REJECTED = "REJECTED";
}