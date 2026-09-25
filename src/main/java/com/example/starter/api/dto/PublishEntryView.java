package com.example.starter.api.dto;

import java.util.List;

/**
 * 发布快照中单个制品一条命中策略的固化视图；无命中策略时许可字段为 null。
 */
public record PublishEntryView(
        String artifactName,
        int artifactVersion,
        Long policyId,
        String textKey,
        Integer textVersion,
        List<String> noticeRegions,
        String hitPath) {
}
