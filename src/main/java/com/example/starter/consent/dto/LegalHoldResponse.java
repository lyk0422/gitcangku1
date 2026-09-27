package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 合法保留登记结果/诊断视图：窗口按 UTC 左闭右开，durationSeconds 为窗口实际时长（实际值，秒），
 * 等于 0 表示零长度窗口（任何时刻都不满足 validFrom &lt;= now &lt; validTo）。
 *
 * @param subjectKey      主体标识
 * @param purpose         用途
 * @param epoch           被保留记录所属授权代次
 * @param recordKey       被保留记录键
 * @param holdReason      保留依据（合成字符串）
 * @param validFrom       窗口起点（UTC，含）
 * @param validTo         窗口终点（UTC，不含）
 * @param durationSeconds 窗口实际时长（实际值）= validTo - validFrom，单位秒
 */
public record LegalHoldResponse(String subjectKey, Purpose purpose, int epoch, String recordKey,
                                String holdReason, Instant validFrom, Instant validTo, long durationSeconds) {
}
