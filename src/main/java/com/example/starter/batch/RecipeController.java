package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeStatusResponse;
import com.example.starter.batch.dto.WeighRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方与称量接口：配方版本创建、批次配方绑定、称量与更正、配方状态查询。
 * 写操作均需 X-Actor-Id 操作人请求头，并以 requestId 幂等。
 */
@RestController
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比最多 4 位小数且总和精确等于 100；版本不可变。
     */
    @PostMapping("/api/recipes")
    public ResponseEntity<String> createRecipe(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CreateRecipeRequest request) {
        return stored(service.createRecipe(actorId, request));
    }

    /**
     * 批次绑定不可变配方版本及目标总质量（克）。
     */
    @PostMapping("/api/batches/{batchKey}/recipe")
    public ResponseEntity<String> bindRecipe(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindRecipeRequest request) {
        return stored(service.bindRecipe(batchKey, actorId, request));
    }

    /**
     * 追加称量：同成分多次称量累加。
     */
    @PostMapping("/api/batches/{batchKey}/weighings")
    public ResponseEntity<String> weigh(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WeighRequest request) {
        return stored(service.weigh(batchKey, actorId, request));
    }

    /**
     * 追加称量更正：指定最近有效称量 ID，以替代量重算。
     */
    @PostMapping("/api/batches/{batchKey}/weighings/corrections")
    public ResponseEntity<String> correct(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody CorrectWeighingRequest request) {
        return stored(service.correct(batchKey, actorId, request));
    }

    /**
     * 批次配方状态：绑定信息、有效称量链与符合性汇总。
     */
    @GetMapping("/api/batches/{batchKey}/recipe")
    public RecipeStatusResponse status(@PathVariable String batchKey) {
        return service.status(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
