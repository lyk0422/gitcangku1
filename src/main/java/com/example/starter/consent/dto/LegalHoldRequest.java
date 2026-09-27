package com.example.starter.consent.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 合法保留登记请求：撤回 epoch 下的记录在保留窗口（左闭右开，UTC）内仍允许受限读取。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH / PERSONALIZATION
 * @param epoch      被保留记录所属授权代次
 * @param recordKey  被保留记录键
 * @param holdReason 保留依据（合成字符串，如合规案号）
 * @param validFrom  保留窗口起点（UTC，含）
 * @param validTo    保留窗口终点（UTC，不含），不得早于 validFrom
 */
public record LegalHoldRequest(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull com.example.starter.consent.Purpose purpose,
        @NotNull @Positive Integer epoch,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 256) String holdReason,
        @NotNull Instant validFrom,
        @NotNull Instant validTo) {
}
