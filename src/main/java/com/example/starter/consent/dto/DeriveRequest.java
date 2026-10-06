package com.example.starter.consent.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 求和派生请求：引用 2 至 5 条当前有效研究记录精确求和，写入目标主体当前研究代次的新键。
 *
 * @param requestId        幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param operator         操作者标识（合成字符串），参与幂等身份
 * @param targetSubjectKey 目标主体标识（合成字符串）
 * @param targetEpoch      目标主体当前研究授权代次（期望版本），从 1 开始
 * @param recordKey        新记录键，目标代次内不得已存在
 * @param sources          2 至 5 条来源引用，可为数值或派生记录，不得重复
 */
public record DeriveRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 128) String targetSubjectKey,
        @NotNull @Min(1) Integer targetEpoch,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Size(min = 2, max = 5) List<@Valid SourceRef> sources) {
}
