package com.example.starter.api.dto;

/**
 * 许可证策略命中视图：锁定图闭包中某制品命中的一条策略及其依赖路径。
 */
public record PolicyHitView(
        long lockFileId,
        String artifactName,
        int artifactVersion,
        long policyId,
        String noticeType,
        String textKey,
        Integer textVersion,
        String hitPath) {
}
