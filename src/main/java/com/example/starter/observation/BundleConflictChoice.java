package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单个冲突字段的裁决选择：对指定冲突登记 id 选择来源 LOCAL/REMOTE/BASE，或给出显式新值 VALUE。
 *
 * @param conflictId     冲突登记自增 id（bundle_conflict.id）
 * @param candidateToken 登记冲突时返回的候选快照指纹，必须原样回传；候选被重新登记后指纹变化，旧请求 409
 * @param source         来源：LOCAL / REMOTE / BASE / VALUE
 * @param value          来源为 VALUE 时的显式新值；其余来源必须为空
 */
public record BundleConflictChoice(
        @NotNull Long conflictId,
        @NotBlank @Size(max = 128) String candidateToken,
        @NotBlank String source,
        @Size(max = 1024) String value) {
}
