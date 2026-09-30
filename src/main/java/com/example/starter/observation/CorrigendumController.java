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

/**
 * 现场观测更正附录 API：观测记录员提交字段差异，复核员与下游读取方按版本查看原始值与当前有效值。
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
     * 创建更正附录：仅已解决观测簇可提交，字段差异以新附录版本表达。
     */
    @PostMapping
    public ResponseEntity<CorrigendumResponse> submit(@PathVariable String observationId,
                                                      @Valid @RequestBody CreateCorrigendumRequest request) {
        CorrigendumService.CorrigendumOutcome outcome =
                corrigendumService.submitCorrigendum(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询指定附录版本；版本 0 返回只读原始快照，旧版本查询始终返回当时有效值。
     */
    @GetMapping("/versions/{version}")
    public CorrigendumVersionView getVersion(@PathVariable String observationId,
                                             @PathVariable @Min(0) int version) {
        return corrigendumService.getVersionView(observationId, version);
    }

    /**
     * 查询附录版本链：串联原始快照与每次更正的操作者、时刻及字段差异。
     */
    @GetMapping
    public CorrigendumHistoryResponse history(@PathVariable String observationId) {
        return corrigendumService.getHistory(observationId);
    }
}
