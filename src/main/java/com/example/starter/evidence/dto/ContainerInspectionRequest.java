package com.example.starter.evidence.dto;

import com.example.starter.evidence.SealResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 容器巡检见证请求。同一次巡检的两名不同保管人提交相同 requestId、
 * 相同见证封条版本与相同封条结论；仅一人确认时不改变任何证物状态。
 *
 * @param requestId        巡检请求幂等键，两名见证人共用
 * @param witnessedVersion 见证人现场核对的封条版本号，必须等于容器当前版本且两名见证人一致
 * @param sealResult       封条结论：ANOMALY 异常 / INTACT 完好
 * @param note             见证备注，可空
 */
public record ContainerInspectionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Integer witnessedVersion,
        @NotNull SealResult sealResult,
        @Size(max = 512) String note) {
}
