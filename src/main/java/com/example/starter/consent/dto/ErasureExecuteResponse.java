package com.example.starter.consent.dto;

import com.example.starter.consent.ErasureStatus;
import com.example.starter.consent.Purpose;

/**
 * 清除执行响应：只含计数与状态，不含任何载荷原文。
 *
 * @param subjectKey    主体标识（合成字符串）
 * @param purpose       用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch         被清除的授权代次
 * @param status        任务状态：PENDING 待清除 / DONE 已清完
 * @param erasedCount   累计已清除载荷条数（不跨代统计）
 * @param erasedInBatch 本次执行实际清除的条数（0 至 5）
 */
public record ErasureExecuteResponse(String subjectKey, Purpose purpose, int epoch,
                                     ErasureStatus status, int erasedCount, int erasedInBatch) {
}
