package com.example.starter.observation;

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

import java.util.List;

/**
 * 观测更正附录 API：对已解决观测簇提交不可变更正附录，并按版本串联原始快照与逐次更正差异。
 */
@RestController
@RequestMapping("/api/observations/{observationId}/corrigenda")
@Validated
public class CorrigendumController {

    private final CorrigendumService corrigendumService;

    public CorrigendumController(CorrigendumService corrigendumService) {
        this.corrigendumService = corrigendumService;
    }

    /**
     * 提交更正附录：未解决冲突簇返回 422；前置附录版本争用返回 409；同 requestId 同参重放原结果，换字段 409。
     */
    @PostMapping
    public ResponseEntity<CorrigendumResponse> create(@PathVariable String observationId,
                                                      @Valid @RequestBody CreateAppendixRequest request) {
        CorrigendumService.CorrigendumOutcome outcome = corrigendumService.create(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前视角：原始快照值与当前有效值。
     */
    @GetMapping("/current")
    public VersionedObservationResponse current(@PathVariable String observationId) {
        return corrigendumService.getCurrentVersioned(observationId);
    }

    /**
     * 按版本查询：version=0 为原始快照，1 起为附录版本；旧版本始终返回当时值。
     */
    @GetMapping("/versions/{version}")
    public VersionedObservationResponse version(@PathVariable String observationId,
                                                @PathVariable @Min(0) int version) {
        return corrigendumService.getVersioned(observationId, version);
    }

    /**
     * 查询附录差异链：原始快照之后每次更正的操作者、时刻及字段旧值/新值，按版本先后排序。
     */
    @GetMapping
    public List<AppendixEntry> list(@PathVariable String observationId) {
        return corrigendumService.listAppendices(observationId);
    }
}
