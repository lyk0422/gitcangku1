package com.example.starter.batch;

import com.example.starter.batch.dto.RecipeDeviation;

import java.util.List;

/**
 * 放行前配方未满足：携带全部成分的目标/实际偏差，HTTP 422。
 */
public class RecipeNotMetException extends RuntimeException {

    private final List<RecipeDeviation> deviations;

    public RecipeNotMetException(String message, List<RecipeDeviation> deviations) {
        super(message);
        this.deviations = deviations;
    }

    public List<RecipeDeviation> deviations() {
        return deviations;
    }
}