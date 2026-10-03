package com.example.starter.blackstart;

import java.time.Instant;

/**
 * 一次步骤确认记录（含成功与被拒绝的失败记录）。
 * 读数单位：电压 V、频率 Hz、功率 kW；recordedAt 为 UTC 时刻；
 * 未提供的读数与成功记录的 rejectReason 为 null。
 */
public record StepAttempt(
        long id,
        long planId,
        int stepNo,
        String requestId,
        String outcome,
        String deviceStatus,
        Double voltageV,
        Double frequencyHz,
        Double powerKw,
        String rejectReason,
        Instant recordedAt) {

    /** 确认成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 被拒绝（读数越界、设备状态错误或跳步）。 */
    public static final String OUTCOME_REJECTED = "REJECTED";
    /** 可成功的设备状态。 */
    public static final String DEVICE_STATUS_NORMAL = "NORMAL";
}