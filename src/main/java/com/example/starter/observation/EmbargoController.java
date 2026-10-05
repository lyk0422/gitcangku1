package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测禁运与到期发布 API：批量设置禁运、禁运明细/诊断列表、发布详情与附页（授权证据查询），
 * 以及公开查询（截止前不返回簇内容，到期后首次查询触发不可变发布快照）。
 */
@RestController
@Validated
public class EmbargoController {

    private final EmbargoService embargoService;

    public EmbargoController(EmbargoService embargoService) {
        this.embargoService = embargoService;
    }

    /**
     * 批量设置禁运：任一簇非法整次回滚；成功返回 201 与各簇设置结果。
     */
    @PostMapping("/api/observations/embargoes")
    public ResponseEntity<SetEmbargoResponse> setEmbargoes(@Valid @RequestBody SetEmbargoRequest request) {
        EmbargoService.SetEmbargoOutcome outcome = embargoService.setEmbargoes(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 禁运诊断列表：按 observationId 字典序稳定排序。
     */
    @GetMapping("/api/observations/embargoes")
    public List<EmbargoDetailResponse> listEmbargoes() {
        return embargoService.listEmbargoes();
    }

    /**
     * 单簇禁运明细（授权）；未设置返回 404。
     */
    @GetMapping("/api/observations/{observationId}/embargo")
    public EmbargoDetailResponse getEmbargo(@PathVariable String observationId) {
        return embargoService.getEmbargo(observationId);
    }

    /**
     * 发布详情（授权证据查询）：首次发布快照 + 全部更正附页；未发布返回 404。
     */
    @GetMapping("/api/observations/{observationId}/release")
    public ReleaseDetailResponse getReleaseDetail(@PathVariable String observationId) {
        return embargoService.getReleaseDetail(observationId);
    }

    /**
     * 公开查询：截止前不返回簇内容；到期后首次查询生成唯一不可变发布快照。
     */
    @GetMapping("/api/public/observations/{observationId}")
    public PublicObservationResponse publicGet(@PathVariable String observationId) {
        return embargoService.publicGet(observationId);
    }

    /**
     * 公开附页查询：仅已发布后可见，按版本升序；未发布返回 404。
     */
    @GetMapping("/api/public/observations/{observationId}/addenda")
    public List<ReleaseDetailResponse.AddendumView> publicAddenda(@PathVariable String observationId) {
        return embargoService.listPublicAddenda(observationId);
    }
}
