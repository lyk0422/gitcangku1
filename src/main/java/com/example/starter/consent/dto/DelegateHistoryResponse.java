package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.DelegateStatus;

/**
 * 委托历史响应：委托当前状态与全部版本，按版本升序。
 *
 * @param delegateKey    委托键
 * @param subjectKey     数据主体标识
 * @param agentKey       代理人标识
 * @param status         状态：ACTIVE 有效 / REVOKED 已撤销
 * @param currentVersion 当前委托版本
 * @param versions       全部委托版本，按版本升序
 */
public record DelegateHistoryResponse(
        String delegateKey,
        String subjectKey,
        String agentKey,
        DelegateStatus status,
        int currentVersion,
        List<DelegateVersionView> versions) {
}
