package com.example.starter.consent.catalog.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 迁移激活请求：预览不写数据，因此激活时须完整回带提案，并提交预览中
 * 全部有效授权与数据记录的 expectedVersion 及映射结果。
 *
 * @param requestId       幂等请求标识；同参重放首次快照，异参 409，失败不占键
 * @param catalogVersion  预览所基于的目录版本
 * @param sourcePurpose   被拆分的 ACTIVE 旧用途代码
 * @param sourceScopeValues 旧用途处理范围，必须与预览一致
 * @param migrationKey    迁移键，全局唯一
 * @param newPurposes     新用途及范围，必须与预览一致
 * @param effectiveFrom   生效窗口起点（UTC，左闭）
 * @param effectiveTo     生效窗口终点（UTC，右开）
 * @param grants          预览中全部有效授权的确认项，集合换序等价
 * @param records         预览中全部数据记录的确认项（含 MAPPED/UNMAPPED），集合换序等价
 */
public record ActivateMigrationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Integer catalogVersion,
        @NotBlank @Size(max = 32) String sourcePurpose,
        @NotEmpty @Size(max = 256) List<@NotBlank @Size(max = 256) String> sourceScopeValues,
        @NotBlank @Size(max = 128) String migrationKey,
        @NotEmpty @Valid @Size(min = 2, max = 10) List<@Valid NewPurposeDef> newPurposes,
        @NotNull Instant effectiveFrom,
        @NotNull Instant effectiveTo,
        @NotNull @Valid List<@Valid ActivateGrantItem> grants,
        @NotNull @Valid List<@Valid ActivateRecordItem> records) {

    public ActivateMigrationRequest {
        sourceScopeValues = List.copyOf(sourceScopeValues);
        newPurposes = List.copyOf(newPurposes);
        grants = List.copyOf(grants);
        records = List.copyOf(records);
    }
}
