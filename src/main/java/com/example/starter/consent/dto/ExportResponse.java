package com.example.starter.consent.dto;

import java.util.List;

/**
 * 导出快照响应：生成与按 exportKey 读取共用；内容为生成时刻固化的不可变快照。
 *
 * @param exportKey   导出标识，全局唯一
 * @param subjectKey  主体标识（合成字符串）
 * @param generatedAt 生成时刻（ISO-8601，UTC 偏移）
 * @param purposes    逐用途固化视图，按用途名字典序排列
 */
public record ExportResponse(
        String exportKey,
        String subjectKey,
        String generatedAt,
        List<SnapshotPurposeView> purposes) {
}
