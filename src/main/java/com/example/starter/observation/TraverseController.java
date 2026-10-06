package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 闭合导线成果 API：生成闭合导线成果（幂等）与按成果标识查询已冻结成果。
 */
@RestController
@RequestMapping("/api/traverses")
public class TraverseController {

    private final TraverseService traverseService;

    public TraverseController(TraverseService traverseService) {
        this.traverseService = traverseService;
    }

    /**
     * 生成闭合导线成果：3 至 10 个不同观测及期望当前版本，顺序有意义；
     * 闭合差越限 422，缺位移或引用墓碑 422，版本非当前 409。
     */
    @PostMapping
    public ResponseEntity<TraverseResponse> generate(@Valid @RequestBody GenerateTraverseRequest request) {
        TraverseService.TraverseOutcome outcome = traverseService.generate(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按成果标识查询已冻结成果；后续观测变更不影响成果内容。
     */
    @GetMapping("/{traverseId}")
    public TraverseResponse getTraverse(@PathVariable String traverseId) {
        return TraverseResponse.of(traverseService.getTraverse(traverseId));
    }
}
