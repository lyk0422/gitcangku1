package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 容器巡检请求。允许提前巡检（inspectedAt 早于容器下次巡检时刻）；
 * nextInspectionAt 必须严格晚于 inspectedAt；result=FAIL 时 note 非空。
 *
 * @param commandKey       幂等命令键（即 inspectKey 命名空间，指纹含检查人/容器版本/实际时刻/结果/说明）
 * @param inspectedAt     实际巡检时刻（UTC）
 * @param result          封签结果：PASS / FAIL
 * @param note            巡检说明，FAIL 时非空
 * @param nextInspectionAt 巡检后新的下次巡检截止时刻（UTC），须严格晚于 inspectedAt
 */
public record ContainerInspectionRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull LocalDateTime inspectedAt,
        @NotBlank @Size(max = 8) String result,
        @Size(max = 512) String note,
        @NotNull LocalDateTime nextInspectionAt) {
}
