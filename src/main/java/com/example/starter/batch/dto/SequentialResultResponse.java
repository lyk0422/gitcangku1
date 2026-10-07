package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.SequentialConclusion;
import com.example.starter.batch.SequentialSample;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 合成序贯检验单次结果响应，携带提交后冻结的精确分数与结论。
 * numerator/denominator 为精确似然比分数（初始 1/1，GOOD 分子乘 3、BAD 分母乘 3）；
 * likelihoodRatio 仅为展示用小数，阈值判定不使用该值；
 * conclusion 为本次结果落定后的序贯结论（CONTINUE/PASS/FAIL/INCONCLUSIVE）；
 * batchStatus 为本次结果落定后的批次状态快照（序贯 FAIL 时为 REJECTED）。
 */
public record SequentialResultResponse(
        String batchKey,
        int seq,
        SequentialSample sample,
        String inspector,
        long numerator,
        long denominator,
        BigDecimal likelihoodRatio,
        SequentialConclusion conclusion,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
