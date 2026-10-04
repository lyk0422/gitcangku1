package com.example.starter.batch;

import com.example.starter.batch.dto.CreateRecipeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方版本接口：创建不可变配方（2～6 种成分，百分比合计精确等于 100）。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    /**
     * 创建配方版本；requestId 幂等，同键同参重放首次响应，同键异参 409。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateRecipeRequest request) {
        StoredResponse response = service.createRecipe(request);
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}