package com.example.starter.consent.catalog.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途拆分迁移的目录提案：合规人员把一个 ACTIVE 旧用途拆分为 2～10 个新用途。
 *
 * @param catalogVersion   客户端所见目录版本（= 当前 catalogGeneration），防止基于过期目录提交
 * @param sourcePurpose    被拆分的 ACTIVE 旧用途代码
 * @param sourceScopeValues 旧用途处理范围；首次拆分初始用途时由合规人员声明并钉住，后续必须与目录一致
 * @param migrationKey     迁移键，全局唯一
 * @param newPurposes      新用途及其处理范围定义（2～10 个），代码唯一
 * @param effectiveFrom    生效窗口起点（UTC，左闭）
 * @param effectiveTo      生效窗口终点（UTC，右开）
 */
public record MigrationProposalRequest(
        @NotNull Integer catalogVersion,
        @NotBlank @Size(max = 32) String sourcePurpose,
        @NotEmpty @Size(max = 256) List<@NotBlank @Size(max = 256) String> sourceScopeValues,
        @NotBlank @Size(max = 128) String migrationKey,
        @NotEmpty @Valid @Size(min = 2, max = 10) List<@Valid NewPurposeDef> newPurposes,
        @NotNull Instant effectiveFrom,
        @NotNull Instant effectiveTo) {

    public MigrationProposalRequest {
        sourceScopeValues = List.copyOf(sourceScopeValues);
        newPurposes = List.copyOf(newPurposes);
    }
}
