package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配方符合性汇总：totalMatches 表示有效称量总质量与目标精确相等；
 * conforms 表示每种成分齐全、总质量相等且每项占比偏差不超过 0.1 个百分点。
 * 作为 422 错误明细时给出各成分差额。
 */
public record RecipeConformanceDetails(
        String batchKey,
        BigDecimal targetMass,
        BigDecimal actualTotalMass,
        BigDecimal totalMassDifference,
        boolean totalMatches,
        boolean conforms,
        List<IngredientTotal> ingredients
) {
}
