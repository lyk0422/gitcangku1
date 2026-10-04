package com.example.starter.batch;

import com.example.starter.batch.dto.CreateRecipeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方版本接口：创建后不可变，供批次绑定。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final BatchService service;

    public RecipeController(BatchService service) {
        this.service = service;
    }

    /**
     * 创建配方版本：2至6种成分，百分比合计精确等于100；X-Actor-Id 为操作人。
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CreateRecipeRequest request) {
        StoredResponse response = service.createRecipe(actorId, request);
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}