package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.RecipeStatusResponse;
import com.example.starter.batch.dto.WeighingRecordResponse;
import com.example.starter.batch.dto.WeighingRequest;
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

import java.util.List;

/**
 * 配方与称量接口：配方版本管理、批次配方绑定、称量与更正、配方状态查询。
 * 所有写操作要求 X-Actor-Id 操作人请求头。
 */
@RestController
@RequestMapping("/api")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比正值、最多 4 位小数、总和恰为 100。
     */
    @PostMapping("/recipes")
    public ResponseEntity<String> createRecipe(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CreateRecipeRequest request) {
        return stored(service.createRecipe(actorId, request));
    }

    /**
     * 查询配方版本。
     */
    @GetMapping("/recipes/{recipeKey}/versions/{version}")
    public RecipeResponse getRecipe(@PathVariable String recipeKey, @PathVariable int version) {
        return service.getRecipe(recipeKey, version);
    }

    /**
     * 批次绑定不可变配方版本与目标总质量（克，最多 6 位小数）。
     */
    @PostMapping("/batches/{batchKey}/recipe-binding")
    public ResponseEntity<String> bindRecipe(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindRecipeRequest request) {
        return stored(service.bindRecipe(batchKey, actorId, request));
    }

    /**
     * 批次配方状态：各成分目标/实际/差额与整体是否满足。
     */
    @GetMapping("/batches/{batchKey}/recipe")
    public RecipeStatusResponse recipeStatus(@PathVariable String batchKey) {
        return service.recipeStatus(batchKey);
    }

    /**
     * 提交称量：同成分多次称量累加；负量、未知成分或超精度返回 400。
     */
    @PostMapping("/batches/{batchKey}/weighings")
    public ResponseEntity<String> weigh(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WeighingRequest request) {
        return stored(service.weigh(batchKey, actorId, request));
    }

    /**
     * 称量更正：指定该成分最近一条有效称量记录，以替代量重算。
     */
    @PostMapping("/batches/{batchKey}/weighing-corrections")
    public ResponseEntity<String> correct(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CorrectWeighingRequest request) {
        return stored(service.correct(batchKey, actorId, request));
    }

    /**
     * 批次称量历史：含已失效（被更正替代）记录。
     */
    @GetMapping("/batches/{batchKey}/weighings")
    public List<WeighingRecordResponse> listWeighings(@PathVariable String batchKey) {
        return service.listWeighings(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
