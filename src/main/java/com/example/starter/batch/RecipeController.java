package com.example.starter.batch;

import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.CreateRecipeRequest;
import com.example.starter.batch.dto.RecipeResponse;
import com.example.starter.batch.dto.WeighRequest;
import com.example.starter.batch.dto.WeighingChainResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方版本与配方称量接口。
 */
@RestController
@RequestMapping("/api")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    /**
     * 创建配方版本：2～6 种成分，百分比最多 4 位小数、正值、合计精确等于 100。
     */
    @PostMapping("/recipes")
    public ResponseEntity<String> createRecipe(@Valid @RequestBody CreateRecipeRequest request) {
        return stored(service.createRecipe(request));
    }

    /**
     * 查询配方版本。
     */
    @GetMapping("/recipes/{recipeKey}")
    public RecipeResponse recipe(@PathVariable String recipeKey) {
        return service.getRecipe(recipeKey);
    }

    /**
     * 配方称量：同成分多次称量累加；未知成分、负量、超精度返回 400。
     */
    @PostMapping("/batches/{batchKey}/weighings")
    public ResponseEntity<String> weigh(@PathVariable String batchKey,
                                        @Valid @RequestBody WeighRequest request) {
        return stored(service.weigh(batchKey, request));
    }

    /**
     * 称量更正：指定该成分最近有效称量记录ID，以替代量重算而不是重复累加。
     */
    @PostMapping("/batches/{batchKey}/weighing-corrections")
    public ResponseEntity<String> correct(@PathVariable String batchKey,
                                          @Valid @RequestBody CorrectWeighingRequest request) {
        return stored(service.correct(batchKey, request));
    }

    /**
     * 批次称量链：绑定信息、全部称量记录（含已失效）、各成分差额与门禁结论。
     */
    @GetMapping("/batches/{batchKey}/weighings")
    public WeighingChainResponse chain(@PathVariable String batchKey) {
        return service.chain(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
