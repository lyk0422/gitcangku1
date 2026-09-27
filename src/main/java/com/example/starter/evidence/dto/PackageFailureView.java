package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 建包/增删整包失败响应体：HTTP 422，整包不生成且不留半成品。
 * 逐件诊断按规范件次排序，失败原因码互不相同、可区分。
 *
 * @param status      HTTP 状态码（422）
 * @param caseKey     所属案件键
 * @param asOfUtc     截至 UTC 时刻
 * @param message     总体失败说明
 * @param diagnoses   逐件链诊断（仅含未通过项）
 * @param timestamp   失败时间（Asia/Shanghai）
 */
public record PackageFailureView(
        int status,
        String caseKey,
        LocalDateTime asOfUtc,
        String message,
        List<ItemDiagnosisView> diagnoses,
        LocalDateTime timestamp) {
}
