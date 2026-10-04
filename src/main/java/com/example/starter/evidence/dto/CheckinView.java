package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 签到记录视图。
 *
 * @param checkpointNo  签到检查点号
 * @param transporterId 提交签到的运送人
 * @param sealNo        签到时提交的封条编号
 * @param arrivedAt     到达时刻（UTC）
 */
public record CheckinView(
        int checkpointNo,
        String transporterId,
        String sealNo,
        LocalDateTime arrivedAt) {
}