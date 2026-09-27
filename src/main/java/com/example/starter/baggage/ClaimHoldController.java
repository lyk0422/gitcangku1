package com.example.starter.baggage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.baggage.BaggageDtos.ClaimHoldDetail;
import com.example.starter.baggage.BaggageDtos.ClaimHoldHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldListResponse;
import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewResponse;

/**
 * 行李认领冻结 REST 入口：冻结登记、异人复核、二次确认解除与明细/历史/诊断查询。
 */
@RestController
@RequestMapping("/api")
public class ClaimHoldController {

    private final ClaimHoldService claimHoldService;

    public ClaimHoldController(ClaimHoldService claimHoldService) {
        this.claimHoldService = claimHoldService;
    }

    /** 登记认领冻结：行李进入 CLAIM_HOLD，OPEN 航段清单在同一事务内移出。 */
    @PostMapping("/bags/{bagTag}/claim-hold")
    public ResponseEntity<ClaimHoldResponse> hold(@PathVariable String bagTag,
                                                  @Valid @RequestBody ClaimHoldRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimHoldService.hold(bagTag, request));
    }

    /** 复核：须由不同客服提交，核验摘要不匹配返回 422。 */
    @PostMapping("/claim-holds/{claimKey}/review")
    public ClaimReviewResponse review(@PathVariable String claimKey,
                                      @Valid @RequestBody ClaimReviewRequest request) {
        return claimHoldService.review(claimKey, request);
    }

    /** 解除确认（第二次确认）：原子恢复行李为冻结前可交接状态。 */
    @PostMapping("/claim-holds/{claimKey}/release")
    public ClaimReleaseResponse release(@PathVariable String claimKey,
                                        @Valid @RequestBody ClaimReleaseRequest request) {
        return claimHoldService.release(claimKey, request);
    }

    /** 行李当前生效冻结单明细查询。 */
    @GetMapping("/bags/{bagTag}/claim-hold")
    public ClaimHoldDetail getActiveHold(@PathVariable String bagTag) {
        return claimHoldService.getActiveHold(bagTag);
    }

    /** 行李冻结链历史查询：不可变链记录按插入顺序返回。 */
    @GetMapping("/bags/{bagTag}/claim-hold/history")
    public ClaimHoldHistoryResponse getHoldHistory(@PathVariable String bagTag) {
        return claimHoldService.getHoldHistory(bagTag);
    }

    /** 按冻结单号查询明细（含已解除）。 */
    @GetMapping("/claim-holds/{claimKey}")
    public ClaimHoldDetail getHold(@PathVariable String claimKey) {
        return claimHoldService.getHold(claimKey);
    }

    /** 冻结单诊断清单：可选 status 过滤（ACTIVE/REVIEWED/RELEASED）。 */
    @GetMapping("/claim-holds")
    public ClaimHoldListResponse listHolds(@RequestParam(required = false) String status) {
        return claimHoldService.listHolds(status);
    }
}
