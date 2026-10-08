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
 * 断面成果 API：由同断面当前版本断面点与整数水面高程 H 创建水域面积成果，并按 resultId 查询。
 */
@RestController
@RequestMapping("/api/section-results")
public class SectionResultController {

    private final SectionResultService sectionResultService;

    public SectionResultController(SectionResultService sectionResultService) {
        this.sectionResultService = sectionResultService;
    }

    /**
     * 创建断面成果：核验全部源版本，冻结完整点列、H 与精确面积。
     */
    @PostMapping
    public ResponseEntity<SectionResultResponse> create(@Valid @RequestBody CreateSectionResultRequest request) {
        SectionResultService.SectionResultOutcome outcome = sectionResultService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 resultId 查询成果；sourceUpdated 实时反映源观测是否在成果创建后变更。
     */
    @GetMapping("/{resultId}")
    public SectionResultResponse get(@PathVariable String resultId) {
        return sectionResultService.get(resultId);
    }
}
