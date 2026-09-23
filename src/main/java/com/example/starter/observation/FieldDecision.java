package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联合裁决的逐字段决定：对某条观测的单个未决字段冲突选择来源；EXPLICIT 时必须同时给出显式新值。
 *
 * @param observationId 冲突所属观测记录唯一标识
 * @param field         字段名：location / reading / note
 * @param source        取值来源：LOCAL / REMOTE / BASE / EXPLICIT
 * @param value         显式新值；仅当 source=EXPLICIT 时必填且必须通过该字段的格式校验
 * @param baseVersion   墓碑恢复选择 BASE 来源时的历史版本号；其余场景为空
 */
public record FieldDecision(
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 16) String field,
        @NotNull ArbitrationSource source,
        @Size(max = 1024) String value,
        @Min(1) Integer baseVersion) {
}
