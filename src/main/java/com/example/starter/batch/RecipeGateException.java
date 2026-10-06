package com.example.starter.batch;

import com.example.starter.batch.dto.IngredientDifference;

import java.util.List;

/**
 * 配方门禁未通过：批次已绑定配方但投料不达配方，放行前返回 422 并携带各成分差额。
 */
public class RecipeGateException extends RuntimeException {

    private final List<IngredientDifference> differences;

    public RecipeGateException(String message, List<IngredientDifference> differences) {
        super(message);
        this.differences = differences;
    }

    public List<IngredientDifference> differences() {
        return differences;
    }
}
