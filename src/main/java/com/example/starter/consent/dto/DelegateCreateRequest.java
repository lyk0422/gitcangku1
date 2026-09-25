package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 委托创建请求：数据主体为代理人授予用途范围委托。
 *
 * @param delegateKey 委托键（幂等键），同键重放需参数指纹一致，失败不占键
 * @param subjectKey  数据主体标识（合成字符串）
 * @param agentKey    代理人标识（合成字符串），不得与主体相同
 * @param purposes    委托用途集合，规范化后非空；空集合返回 422
 * @param validFrom   有效期起（UTC，左闭）
 * @param validTo     有效期止（UTC，右开），必须晚于 validFrom
 */
public record DelegateCreateRequest(
        @NotBlank @Size(max = 128) String delegateKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 128) String agentKey,
        @NotNull List<Purpose> purposes,
        @NotNull Instant validFrom,
        @NotNull Instant validTo) {
}
