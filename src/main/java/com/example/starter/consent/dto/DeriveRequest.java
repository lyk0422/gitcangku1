package com.example.starter.consent.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 派生记录请求：引用 2 至 5 条当前有效研究记录（数值或派生），精确求和写入目标主体新键。
 *
 * @param requestId        幂等请求标识，同 requestId 同参数重放返回首次响应
 * @param operator         操作者标识，参与幂等身份结构化比较
 * @param targetSubjectKey 目标主体标识（合成字符串），用途固定为 RESEARCH 研究
 * @param recordKey        目标新记录键，同一代内唯一，已存在返回 409
 * @param expectedEpoch    期望的目标主体当前研究代次，不一致返回 409
 * @param sources          2 至 5 条来源引用，重复来源返回 400
 */
public record DeriveRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 128) String targetSubjectKey,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Min(1) Integer expectedEpoch,
        @NotNull @Size(min = 2, max = 5) List<@Valid SourceRef> sources) {
}
