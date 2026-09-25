package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.starter.consent.DelegateStatus;
import com.example.starter.consent.Purpose;

/**
 * 委托响应：返回委托键、主体、代理、规范化用途集合、绑定的授权代次、UTC 有效期与版本。
 *
 * @param delegateKey     委托键
 * @param subjectKey      数据主体标识
 * @param agentKey        代理人标识
 * @param purposes        规范化用途集合（去重并按用途名排序）
 * @param epochs          各用途绑定的授权代次（用途 -> 代次）
 * @param validFrom       有效期起（UTC，左闭）
 * @param validTo         有效期止（UTC，右开）
 * @param delegateVersion 委托版本，从 1 开始
 * @param status          状态：ACTIVE 有效 / REVOKED 已撤销
 */
public record DelegateResponse(
        String delegateKey,
        String subjectKey,
        String agentKey,
        List<Purpose> purposes,
        Map<Purpose, Integer> epochs,
        Instant validFrom,
        Instant validTo,
        int delegateVersion,
        DelegateStatus status) {
}
