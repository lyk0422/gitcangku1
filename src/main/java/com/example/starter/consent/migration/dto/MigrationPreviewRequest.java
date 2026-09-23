package com.example.starter.consent.migration.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用途拆分迁移的预览/规格请求：提交目录版本、被拆分旧用途、迁移键、
 * 左闭右开 UTC 生效窗口与 2～10 个新用途映射。预览只读，不写数据。
 *
 * @param catalogVersion 提交时基于的目录代次，必须等于当前最新代次
 * @param sourcePurpose  被拆分的 ACTIVE 旧用途代码
 * @param migrationKey   迁移键，全局唯一
 * @param effectiveStart 生效窗口起点（UTC，左闭，含）
 * @param effectiveEnd   生效窗口终点（UTC，右开，不含）
 * @param targets        新用途映射列表（2～10 个）
 */
public record MigrationPreviewRequest(
        @NotNull Long catalogVersion,
        @NotBlank @Size(max = 64) String sourcePurpose,
        @NotBlank @Size(max = 128) String migrationKey,
        @NotNull Instant effectiveStart,
        @NotNull Instant effectiveEnd,
        @NotNull @Valid @Size(min = 2, max = 10) List<PurposeTargetDto> targets) {
}
