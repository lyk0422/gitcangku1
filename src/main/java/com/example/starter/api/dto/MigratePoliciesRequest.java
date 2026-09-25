package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 批量策略迁移请求：为多个锁定图指定迁移后的目标策略版本。
 *
 * <p>服务端先预校验所有锁定图按当前仓库状态命中目标版本后均合规，
 * 任一锁定图最终命中不合规则整体 422 且不写入任何绑定。
 *
 * @param operator 操作者（写入 provenanceKey 指纹）
 */
public record MigratePoliciesRequest(
        String operator,
        @NotEmpty @Valid List<MigrationTarget> targets) {

    public MigratePoliciesRequest {
        if (targets == null) {
            targets = List.of();
        } else {
            targets = List.copyOf(targets);
        }
    }

    /** 单个锁定图的迁移目标。 */
    public record MigrationTarget(
            @NotNull Long lockFileId,
            @NotNull @PositiveOrZero Integer targetPolicyVersion) {
    }
}
