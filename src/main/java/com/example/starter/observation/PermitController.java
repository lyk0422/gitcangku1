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

import java.util.List;

/**
 * 观测采样许可 API：创建（交集重叠 409）、提前结束、撤销、许可计数与复核快照查询。
 */
@RestController
@RequestMapping("/api/permits")
@Validated
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    /**
     * 创建采样许可；同一设备的有效许可在网格与时间交集上重叠时返回 409 PERMIT_OVERLAP。
     */
    @PostMapping
    public ResponseEntity<PermitResponse> create(@Valid @RequestBody CreatePermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 提前结束许可：只影响之后采样，不删除已接收观测。
     */
    @PostMapping("/{permitId}/end")
    public ResponseEntity<PermitResponse> end(@PathVariable String permitId,
                                              @Valid @RequestBody EndPermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.end(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销许可：未合并观测标记 PERMIT_REVIEW 并写入复核快照，已人工裁决簇不重算。
     */
    @PostMapping("/{permitId}/revoke")
    public ResponseEntity<PermitResponse> revoke(@PathVariable String permitId,
                                                 @Valid @RequestBody RevokePermitRequest request) {
        PermitService.PermitOutcome outcome = permitService.revoke(permitId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询许可（含观测计数：已用/上限/剩余）。
     */
    @GetMapping("/{permitId}")
    public PermitResponse getPermit(@PathVariable String permitId) {
        return permitService.getPermit(permitId);
    }

    /**
     * 查询许可撤销时写入的复核快照。
     */
    @GetMapping("/{permitId}/review-snapshots")
    public List<PermitReviewSnapshot> listReviewSnapshots(@PathVariable String permitId) {
        return permitService.listReviewSnapshots(permitId);
    }
}
