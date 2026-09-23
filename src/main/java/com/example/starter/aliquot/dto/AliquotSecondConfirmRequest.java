package com.example.starter.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 第二次审核确认请求。必须携带申请版本和全部母样版本；
 * 期间任一母样转移、借出、封条异常、数量或保管人变化（版本不一致）均 409。
 *
 * @param commandKey      幂等命令键
 * @param requestVersion  申请版本：第一次确认后为 1
 * @param sampleVersions  全部母样当前版本（sampleKey -> version），键集合须与申请明细完全一致
 */
public record AliquotSecondConfirmRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull Long requestVersion,
        @NotNull @Size(min = 2, max = 20) Map<String, Long> sampleVersions) {
}
