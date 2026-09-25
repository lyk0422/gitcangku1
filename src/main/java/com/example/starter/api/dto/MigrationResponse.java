package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量策略迁移结果：全部绑定原子生效时返回每个锁定图的最终命中版本。
 */
public record MigrationResponse(List<MigrationItem> results) {

    /** 单个锁定图迁移结果。 */
    public record MigrationItem(long lockFileId, String lockfileName, int policyVersion) {
    }
}
