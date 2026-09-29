package com.example.starter.consent.ledger.dto;

import java.time.Instant;
import java.util.List;

/**
 * 受限批量查询成功响应：余额扣减与本快照在同一事务内固化，重放返回本快照且不再扣减。
 *
 * @param requestId     幂等请求标识
 * @param subjectKey    主体标识（操作者）
 * @param purpose       用途
 * @param epoch        查询命中的授权代次
 * @param objectCount   规范化后对象数量（单位：个，去重后）
 * @param objectDigest  规范化对象集合摘要（规范化 JSON 经 SHA-256 十六进制编码）
 * @param balanceBefore 扣减前余额（单位：个）
 * @param balanceAfter  扣减后余额（单位：个）
 * @param ledgerSeq     对应账本条目序号
 * @param occurredAtUtc 查询裁决时间（UTC，微秒精度）
 * @param results       全部对象的查询结果，按 recordKey 字典序排列；失败时不返回任何部分数据
 */
public record RestrictedQueryResponse(
        String requestId,
        String subjectKey,
        String purpose,
        int epoch,
        int objectCount,
        String objectDigest,
        long balanceBefore,
        long balanceAfter,
        long ledgerSeq,
        Instant occurredAtUtc,
        List<Item> results) {

    /**
     * 单个对象的查询结果。
     *
     * @param recordKey 记录键
     * @param payload   记录内容（合成字符串）
     */
    public record Item(String recordKey, String payload) {
    }
}
