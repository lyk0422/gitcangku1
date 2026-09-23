package com.example.starter.api;

import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitRedeemDto;
import com.example.starter.api.dto.PermitResult;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.service.ExemptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多区域豁免包与带核销飞行审核 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class ExemptionController {

    private final ExemptionService service;

    public ExemptionController(ExemptionService service) {
        this.service = service;
    }

    /** 签发豁免包。 */
    @PostMapping("/permits")
    public ResponseEntity<MutationResponse> issuePermit(@Valid @RequestBody PermitIssueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issuePermit(request));
    }

    /** 撤销豁免包未使用余额。 */
    @PostMapping("/permits/revoke")
    public ResponseEntity<MutationResponse> revokePermit(@Valid @RequestBody PermitRevokeRequest request) {
        return ResponseEntity.ok(service.revokePermit(request));
    }

    /** 提交带豁免核销的飞行审核。 */
    @PostMapping("/flight-reviews")
    public ResponseEntity<MutationResponse> flightReview(@Valid @RequestBody FlightReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.flightReview(request));
    }

    /** 只读查询豁免包余额与区域项。 */
    @GetMapping("/permits/{permitKey}")
    public PermitResult getPermit(@PathVariable String permitKey) {
        return service.getPermit(permitKey);
    }

    /** 只读查询豁免包核销流水。 */
    @GetMapping("/permits/{permitKey}/redeems")
    public List<PermitRedeemDto> getRedeems(@PathVariable String permitKey) {
        return service.getRedeems(permitKey);
    }

    /** 只读查询单条飞行审核快照。 */
    @GetMapping("/flight-reviews/{reviewId}")
    public FlightReviewResultDto getFlightReview(@PathVariable String reviewId) {
        return service.getFlightReview(reviewId);
    }

    /** 只读查询某 flightKey 的审核历史（含 BLOCKED 尝试）。 */
    @GetMapping("/flights/{flightKey}/reviews")
    public List<FlightReviewResultDto> getFlightHistory(@PathVariable String flightKey) {
        return service.getFlightHistory(flightKey);
    }
}
