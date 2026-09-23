package com.example.starter.api;

import com.example.starter.api.dto.ConsumptionDto;
import com.example.starter.api.dto.FlightReviewRequest;
import com.example.starter.api.dto.FlightReviewResultDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PermitIssueRequest;
import com.example.starter.api.dto.PermitRevokeRequest;
import com.example.starter.api.dto.PermitView;
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
 * 多区域豁免包配额与核销 API。
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

    /** 带 flightKey 与 reviewAt 的航线审核（命中区域由豁免包配额核销后放行）。 */
    @PostMapping("/flight-reviews")
    public ResponseEntity<MutationResponse> reviewFlight(@Valid @RequestBody FlightReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reviewFlight(request));
    }

    /** 只读查询豁免包余额。 */
    @GetMapping("/permits/{permitKey}")
    public PermitView getPermit(@PathVariable String permitKey) {
        return service.getPermit(permitKey);
    }

    /** 只读查询豁免包核销历史。 */
    @GetMapping("/permits/{permitKey}/consumptions")
    public List<ConsumptionDto> getConsumptions(@PathVariable String permitKey) {
        return service.getConsumptions(permitKey);
    }

    /** 只读查询航班审核历史。 */
    @GetMapping("/flight-reviews/{reviewId}")
    public FlightReviewResultDto getFlightReview(@PathVariable String reviewId) {
        return service.getFlightReview(reviewId);
    }
}
