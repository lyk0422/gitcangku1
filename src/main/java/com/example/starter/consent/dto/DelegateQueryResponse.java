package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 代理批量查询响应：全部主体通过校验后返回数据并生成快照。
 *
 * @param queryId  批次查询标识（即查询请求幂等键）
 * @param agentKey 代理人标识
 * @param purposes 规范化请求用途集合
 * @param subjects 各主体结果，按主体标识排序
 */
public record DelegateQueryResponse(
        String queryId,
        String agentKey,
        List<Purpose> purposes,
        List<DelegateSubjectResult> subjects) {
}
