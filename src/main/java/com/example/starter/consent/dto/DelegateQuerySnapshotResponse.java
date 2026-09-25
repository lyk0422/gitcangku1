package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 批次查询快照响应：成功批次固化的委托与授权版本。
 *
 * @param queryId  批次查询标识
 * @param agentKey 代理人标识
 * @param purposes 规范化请求用途集合
 * @param subjects 各主体固化的委托与授权版本，按主体标识排序
 */
public record DelegateQuerySnapshotResponse(
        String queryId,
        String agentKey,
        List<Purpose> purposes,
        List<SnapshotSubjectView> subjects) {
}
