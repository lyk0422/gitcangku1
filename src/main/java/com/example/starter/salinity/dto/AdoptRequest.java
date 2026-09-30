package com.example.starter.salinity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 质控采纳请求：在指定航次/站位/深度上采纳某一条原始样本版本。
 *
 * @param voyageCode 执行质控的航次编号（必须与样本所属航次一致，否则 422）
 * @param sampleNo   待采纳样本的采样编号
 * @param requestId  质控请求幂等键（全局唯一）
 */
public record AdoptRequest(
        @NotBlank @Size(max = 64) String voyageCode,
        @NotBlank @Size(max = 96) String sampleNo,
        @NotBlank @Size(max = 96) String requestId) {
}
