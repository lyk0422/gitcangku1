package com.example.starter.batch;

import com.example.starter.batch.dto.BindRecipeRequest;
import com.example.starter.batch.dto.CorrectWeighingRequest;
import com.example.starter.batch.dto.WeighRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次配方绑定与称量接口：绑定不可变配方版本、称量累加、称量更正。
 * 所有写操作以 requestId 幂等，操作人经 X-Actor-Id 请求头提供。
 */
@RestController
@RequestMapping("/api/batches")
public class WeighingController {

    private final RecipeService service;

    public WeighingController(RecipeService service) {
        this.service = service;
    }

    /**
     * 绑定配方版本及目标总质量（克）；一批仅可绑定一次。
     */
    @PostMapping("/{batchKey}/recipe-binding")
    public ResponseEntity<String> bind(@PathVariable String batchKey,
                                       @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                       @Valid @RequestBody BindRecipeRequest request) {
        return stored(service.bind(batchKey, actorId, request));
    }

    /**
     * 记录称量：同成分多次称量累加。
     */
    @PostMapping("/{batchKey}/weighings")
    public ResponseEntity<String> weigh(@PathVariable String batchKey,
                                        @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                        @Valid @RequestBody WeighRequest request) {
        return stored(service.weigh(batchKey, actorId, request));
    }

    /**
     * 称量更正：指定该成分最近有效称量ID，以替代量重算。
     */
    @PostMapping("/{batchKey}/weighing-corrections")
    public ResponseEntity<String> correct(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @Valid @RequestBody CorrectWeighingRequest request) {
        return stored(service.correct(batchKey, actorId, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}