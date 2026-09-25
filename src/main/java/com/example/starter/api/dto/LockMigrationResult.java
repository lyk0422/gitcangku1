package com.example.starter.api.dto;

import com.example.starter.domain.PolicyViolation;

import java.util.List;

/**
 * 单个锁定图在候选策略下的预校验结果。
 */
public record LockMigrationResult(
        long lockFileId,
        String rootName,
        int rootVersion,
        List<PolicyViolation> violations) {
}
