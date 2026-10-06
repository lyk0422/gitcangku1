package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 闭合导线成果 API：生成闭合导线成果（冻结输入顺序、版本、闭合差与修正边）与成果查询。
 */
@RestController
@RequestMapping("/api/traverses")
@Validated
public class TraverseController {

    private final TraverseService traverseService;

    public TraverseController(TraverseService traverseService) {
        this.traverseService = traverseService;
    }

    /**
     * 生成闭合导线成果：3 至 10 条不同观测按顺序组成闭合导线，核对全部引用当前版本后
     * 计算闭合差并逐边修正，原子冻结成果。
     */
    @PostMapping
    public ResponseEntity<TraverseResponse> generate(@Valid @RequestBody GenerateTraverseRequest request) {
        TraverseService.TraverseOutcome outcome = traverseService.generate(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按成果标识查询冻结成果。
     */
    @GetMapping("/{resultId}")
    public TraverseResponse getTraverse(@PathVariable String resultId) {
        return traverseService.getTraverse(resultId);
    }
}
