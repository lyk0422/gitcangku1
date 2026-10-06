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
 * 持续越限区间分析 API：生成（冻结引用、阈值与区间）与查询（含源更新标记）。
 */
@RestController
@RequestMapping("/api/excursion-analyses")
@Validated
public class ExcursionAnalysisController {

    private final ExcursionAnalysisService excursionAnalysisService;

    public ExcursionAnalysisController(ExcursionAnalysisService excursionAnalysisService) {
        this.excursionAnalysisService = excursionAnalysisService;
    }

    /**
     * 生成越限分析：引用 1 至 20 条分钟观测当前版本，按阈值 H 与连续分钟数 N（2 至 5）判定区间。
     */
    @PostMapping
    public ResponseEntity<ExcursionAnalysisResponse> create(
            @Valid @RequestBody CreateExcursionAnalysisRequest request) {
        ExcursionAnalysisService.AnalysisOutcome outcome = excursionAnalysisService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询已生成的分析：返回冻结内容，并标记源观测是否已有更新。
     */
    @GetMapping("/{analysisId}")
    public ExcursionAnalysisResponse get(@PathVariable String analysisId) {
        return excursionAnalysisService.get(analysisId);
    }
}
