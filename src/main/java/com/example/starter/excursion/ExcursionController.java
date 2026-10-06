package com.example.starter.excursion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 越限观测与区间分析 API：创建、离线提交（三方合并）、删除、当前/历史版本查询、
 * 生成越限区间分析与查询已冻结分析结果。
 */
@RestController
@RequestMapping("/api/excursions")
@Validated
public class ExcursionController {

    private final ExcursionService excursionService;
    private final ExcursionAnalysisService analysisService;

    public ExcursionController(ExcursionService excursionService,
                               ExcursionAnalysisService analysisService) {
        this.excursionService = excursionService;
        this.analysisService = analysisService;
    }

    /**
     * 创建越限观测，初始版本为 1。
     */
    @PostMapping("/observations")
    public ResponseEntity<ExcursionResponse> create(@Valid @RequestBody CreateExcursionRequest request) {
        ExcursionService.WriteOutcome outcome = excursionService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与三个可编辑字段完整候选值，执行三方合并。
     */
    @PostMapping("/observations/{observationId}/merge")
    public ResponseEntity<ExcursionResponse> merge(@PathVariable String observationId,
                                                   @Valid @RequestBody MergeExcursionRequest request) {
        ExcursionService.WriteOutcome outcome = excursionService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除越限观测：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/observations/{observationId}/delete")
    public ResponseEntity<ExcursionResponse> delete(@PathVariable String observationId,
                                                    @Valid @RequestBody DeleteExcursionRequest request) {
        ExcursionService.WriteOutcome outcome = excursionService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/observations/{observationId}")
    public ExcursionResponse getCurrent(@PathVariable String observationId) {
        return excursionService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/observations/{observationId}/versions/{version}")
    public ExcursionResponse getVersion(@PathVariable String observationId,
                                        @PathVariable @Min(1) int version) {
        return excursionService.getVersion(observationId, version);
    }

    /**
     * 生成越限区间分析：引用一组当前版本观测，冻结阈值、引用与区间结果。
     */
    @PostMapping("/analyses")
    public ResponseEntity<AnalysisResponse> analyze(@Valid @RequestBody AnalyzeExcursionRequest request) {
        ExcursionAnalysisService.AnalyzeOutcome outcome = analysisService.analyze(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询已冻结的分析结果；sourceUpdated 标明引用观测是否已有源更新。
     */
    @GetMapping("/analyses/{analysisId}")
    public AnalysisResponse getAnalysis(@PathVariable String analysisId) {
        return analysisService.getAnalysis(analysisId);
    }
}
