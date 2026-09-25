package com.example.starter.api.dto;

/**
 * 告知缺失/不满足视图：发布门禁校验失败单条原因，reason 取值
 * MISSING_NOTICE / NOTICE_TEXT_NOT_APPROVED / NOTICE_REGION_NOT_COVERED。
 */
public record MissingNoticeView(
        long lockFileId,
        String artifactName,
        int artifactVersion,
        long policyId,
        String reason,
        String hitPath) {
}
