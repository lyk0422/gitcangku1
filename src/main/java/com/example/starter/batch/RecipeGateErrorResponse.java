package com.example.starter.batch;

import com.example.starter.batch.dto.IngredientDifference;

import java.util.List;

/**
 * 配方门禁 422 响应体：code + message + 全部成分差额（含合格与不合格项）。
 */
public record RecipeGateErrorResponse(String code, String message,
                                      List<IngredientDifference> ingredients) {
}
