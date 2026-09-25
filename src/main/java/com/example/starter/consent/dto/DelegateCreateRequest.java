package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.Set;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 委托创建请求：主体为代理人授予用途范围委托。
 * delegateKey 由主体、代理、授权代次、规范化用途、UTC 区间与版本指纹生成，同键重放返回原委托。
 *
 * @param subjectKey      主体标识（合成字符串）
 * @param delegateId      代理人标识，不得与主体相同（否则 422）
 * @param purposes        用途集合，规范化（去重升序）后为空返回 422
 * @param validFrom       UTC 有效期起点（含），ISO-8601
 * @param validTo         UTC 有效期终点（不含），ISO-8601；必须晚于起点
 * @param delegateVersion 委托版本，从 1 开始
 */
public record DelegateCreateRequest(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 128) String delegateId,
        @NotNull Set<Purpose> purposes,
        @NotNull Instant validFrom,
        @NotNull Instant validTo,
        @Min(1) int delegateVersion) {
}
