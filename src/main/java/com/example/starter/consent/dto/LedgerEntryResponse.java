package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 用途账本条目（明细）：账本只追加，后续撤回或额度调整不改写已扣减记录。
 *
 * <p>时间统一使用 UTC，毫秒精度；字段含义与可空性长期稳定。
 *
 * @param ledgerSeq            账本全局顺序号（提交顺序，单调递增）
 * @param requestId            触发扣减的幂等请求标识
 * @param subjectKey           主体标识
 * @param purpose              查询用途
 * @param epoch                扣减发生时的授权代次
 * @param canonicalSetDigest   对象规范集合摘要（SHA-256 十六进制），原始集合经 JSON 转义后排序摘要，不使用裸分隔符拼接
 * @param objectCount          本次核销对象数（单位：对象数）
 * @param balanceBefore        扣减前剩余余额（单位：对象数）
 * @param balanceAfter         扣减后剩余余额（单位：对象数）
 * @param quotaLimitAtDeduction 扣减时总额度快照（单位：对象数）
 * @param querySnapshot        查询快照（完整成功响应 JSON 原文）
 * @param fingerprint          证据指纹：覆盖操作者、操作类型、规范化集合与全部结果相关字段
 * @param occurredAtUtc        扣减发生时间（UTC，毫秒精度，ISO-8601）
 */
public record LedgerEntryResponse(long ledgerSeq, String requestId, String subjectKey, Purpose purpose,
                                  int epoch, String canonicalSetDigest, int objectCount,
                                  long balanceBefore, long balanceAfter, long quotaLimitAtDeduction,
                                  String querySnapshot, String fingerprint, String occurredAtUtc) {
}
