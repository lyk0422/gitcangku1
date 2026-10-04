package com.example.starter.batch.dto;

import java.util.List;

/**
 * 配方不达标（422）错误响应：在统一错误体基础上附带各成分差额明细。
 */
public record RecipeDeviationErrorResponse(
        String code,
        String message,
        List<IngredientDifference> differences
) {
}