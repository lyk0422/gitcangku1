package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量策略迁移预校验结果：覆盖全部锁定图的最终命中，不产生任何写入。
 */
public record MigrationCheckResponse(
        int minLevel,
        List<String> allowedRepos,
        List<LockMigrationResult> results) {
}
