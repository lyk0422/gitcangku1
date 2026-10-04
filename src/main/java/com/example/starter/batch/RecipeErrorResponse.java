package com.example.starter.batch;

import com.example.starter.batch.dto.RecipeDeviation;

import java.util.List;

/**
 * 配方未满足错误响应：在统一错误结构之外附带各成分差额明细。
 */
public record RecipeErrorResponse(String code, String message, List<RecipeDeviation> deviations) {
}