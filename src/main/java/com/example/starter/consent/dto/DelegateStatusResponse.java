package com.example.starter.consent.dto;

import com.example.starter.consent.DelegateStatus;

/**
 * 委托状态响应：返回委托键、当前版本与状态。
 *
 * @param delegateKey     委托键
 * @param delegateVersion 当前委托版本
 * @param status          状态：ACTIVE 有效 / REVOKED 已撤销
 */
public record DelegateStatusResponse(
        String delegateKey,
        int delegateVersion,
        DelegateStatus status) {
}
