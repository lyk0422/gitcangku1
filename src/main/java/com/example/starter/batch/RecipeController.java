package com.example.starter.batch;

import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方版本接口：创建不可变配方版本与查询。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比正值、最多 4 位小数、合计精确等于 100。
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CreateRecipeRequest request) {
        return stored(service.createRecipe(actorId, request));
    }

    /**
     * 查询配方版本。
     */
    @GetMapping("/{recipeKey}")
    public RecipeResponse get(@PathVariable String recipeKey) {
        return service.getRecipe(recipeKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}