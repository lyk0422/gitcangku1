package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.starter.consent.DelegateStatus;
import com.example.starter.consent.Purpose;

/**
 * 委托响应：返回委托指纹、主体、代理、规范化用途、各用途授权代次、UTC 区间、版本与状态。
 *
 * @param delegateKey     委托指纹（SHA-256）
 * @param subjectKey      主体标识
 * @param delegateId      代理人标识
 * @param purposes        规范化用途集合（升序）
 * @param epochs          创建时各用途授权代次（用途 → 代次）
 * @param validFrom       UTC 有效期起点（含）
 * @param validTo         UTC 有效期终点（不含）
 * @param delegateVersion 委托版本
 * @param status          状态：ACTIVE 有效 / REVOKED 已撤销
 */
public record DelegateResponse(
        String delegateKey,
        String subjectKey,
        String delegateId,
        List<Purpose> purposes,
        Map<Purpose, Integer> epochs,
        Instant validFrom,
        Instant validTo,
        int delegateVersion,
        DelegateStatus status) {
}
