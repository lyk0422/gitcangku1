package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批次配方状态查询响应：绑定信息、当前有效称量链与符合性汇总。
 * frozen 表示称量链已冻结（批次 RELEASED/REJECTED/RECALLED/SPLIT），不再接受称量或更正。
 */
public record RecipeStatusResponse(
        String batchKey,
        String recipeKey,
        Integer version,
        BigDecimal targetMass,
        Long stateVersion,
        boolean frozen,
        boolean conforms,
        List<IngredientTotal> totals,
        List<WeighingEntry> weighings
) {

    /**
     * 有效称量链中的一条记录。inherited 为 true 表示拆分继承生成。
     */
    public record WeighingEntry(
            Long id,
            String ingredient,
            BigDecimal mass,
            Long supersedesId,
            String operator,
            boolean inherited,
            Instant weighedAt
    ) {
    }
}
