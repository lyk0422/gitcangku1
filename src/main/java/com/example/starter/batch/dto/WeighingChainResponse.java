package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次称量链视图：绑定信息、全部称量记录（含已失效）、各成分有效合计与配方门禁结果。
 * 质量单位均为克；未绑定配方时 recipeKey/targetMassG 为 null，记录与差额为空列表。
 */
public record WeighingChainResponse(
        String batchKey,
        String recipeKey,
        String targetMassG,
        long version,
        List<WeighingEntry> records,
        List<IngredientDifference> ingredients,
        boolean recipeSatisfied
) {

    /**
     * 单条称量记录：valid 为 false 表示已被更正替代，仅作历史保留。
     */
    public record WeighingEntry(
            long weighingId,
            String ingredient,
            String massG,
            String operator,
            Long supersedesId,
            boolean valid,
            String createdAt
    ) {
    }
}
