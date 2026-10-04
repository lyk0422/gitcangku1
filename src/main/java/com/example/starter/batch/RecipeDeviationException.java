package com.example.starter.batch;

import com.example.starter.batch.dto.IngredientDifference;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 放行前配方校验未通过：HTTP 422，并携带各成分差额明细；不允许只批准合格成分。
 */
public class RecipeDeviationException extends ApiException {

    private final transient List<IngredientDifference> differences;

    public RecipeDeviationException(String message, List<IngredientDifference> differences) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPE_NOT_MET", message);
        this.differences = differences;
    }

    public List<IngredientDifference> differences() {
        return differences;
    }
}