package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 选手提交器材检录请求（复检也使用本接口，每次新插不可变历史记录）。
 *
 * @param inspectionKey   检录记录ID，全局唯一（兼作幂等键；同键同参重放，异参409）
 * @param equipmentSerial 器材序列号，赛事内同一序列号同一时刻仅可绑定一名未完赛选手
 * @param result          检录结果，仅允许 PASS 或 FAIL
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SubmitInspectionRequest(
        @NotBlank String inspectionKey,
        @NotBlank String equipmentSerial,
        @NotBlank @Pattern(regexp = "PASS|FAIL") String result,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
