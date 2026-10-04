package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次当前有效称量链查询结果：有效称量行、按成分汇总（单位克）与称量链版本。
 */
public record WeighingChainResponse(
        String batchKey,
        String recipeKey,
        BigDecimal targetMassGrams,
        int weighingVersion,
        List<Entry> weighings,
        List<Total> totals
) {

    /**
     * 一条有效称量记录。
     */
    public record Entry(long weighingId, String ingredient, BigDecimal amountGrams,
                        Long replacesWeighingId, Instant createdAt) {
    }

    /**
     * 单成分有效称量合计（单位克）及配方声明百分比。
     */
    public record Total(String ingredient, BigDecimal totalGrams,
                        BigDecimal declaredPercentage) {
    }
}