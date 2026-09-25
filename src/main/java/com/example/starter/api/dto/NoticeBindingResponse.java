package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 告知绑定视图。
 */
public record NoticeBindingResponse(
        long id,
        String scopeType,
        Long lockFileId,
        String artifactName,
        Integer artifactVersion,
        String licenseId,
        String noticeKey,
        int noticeVersion,
        Instant createdAt) {
}
