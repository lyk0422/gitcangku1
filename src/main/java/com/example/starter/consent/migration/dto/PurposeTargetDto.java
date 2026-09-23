package com.example.starter.consent.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 拆分迁移中的新用途规格。
 *
 * @param purpose    新用途代码，一次迁移内唯一，且不得与当前目录已有代码重复
 * @param rangeStart 处理范围起点（左闭，含）
 * @param rangeEnd   处理范围终点（右开，不含）
 * @param supersedes 替代的用途代码，{@code null} 表示无替代关系；合并历史边后不得成环
 */
public record PurposeTargetDto(
        @NotBlank @Size(max = 64) String purpose,
        @NotNull Long rangeStart,
        @NotNull Long rangeEnd,
        @Size(max = 64) String supersedes) {
}
