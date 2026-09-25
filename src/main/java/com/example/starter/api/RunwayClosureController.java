package com.example.starter.api;

import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.FlightActionRequest;
import com.example.starter.api.dto.FlightBatchReviewRequest;
import com.example.starter.api.dto.FlightBatchReviewResult;
import com.example.starter.api.dto.FlightCreateRequest;
import com.example.starter.api.dto.FlightDetailResult;
import com.example.starter.api.dto.FlightEmergencyConvertRequest;
import com.example.starter.api.dto.FlightReviewReasonDto;
import com.example.starter.api.dto.FlightRerouteRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.api.dto.RunwayWindowsResult;
import com.example.starter.service.RunwayClosureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跑道关闭与航班起降段审查 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class RunwayClosureController {

    private final RunwayClosureService service;
    private final ObjectMapper objectMapper;

    public RunwayClosureController(RunwayClosureService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** 登记跑道。 */
    @PostMapping("/runways")
    public ResponseEntity<MutationResponse> createRunway(
            @Valid @RequestBody RunwayCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRunway(request));
    }

    /** 登记跑道关闭窗口（UTC 左闭右开；携带跑道版本；closureKey 指纹幂等）。 */
    @PostMapping("/runways/closures")
    public ResponseEntity<MutationResponse> createClosure(
            @Valid @RequestBody ClosureCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createClosure(request));
    }

    /** 查询跑道全部关闭窗口与当前版本。 */
    @GetMapping("/runways/{runwayId}/closures")
    public RunwayWindowsResult getRunwayWindows(@PathVariable String runwayId) {
        return service.getRunwayWindows(runwayId);
    }

    /** 登记航班起降段。 */
    @PostMapping("/flights")
    public ResponseEntity<MutationResponse> registerFlight(
            @Valid @RequestBody FlightCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerFlight(request));
    }

    /**
     * 航班批量审查：任一航线拒绝则整批不批准。
     * 整批批准返回 200；任一拒绝返回 422 且响应体携带逐航线可区分原因。
     */
    @PostMapping("/flight-reviews")
    public ResponseEntity<MutationResponse> reviewBatch(
            @Valid @RequestBody FlightBatchReviewRequest request) {
        MutationResponse response = service.reviewBatch(request);
        FlightBatchReviewResult result = objectMapper.convertValue(
                response.data(), FlightBatchReviewResult.class);
        HttpStatus status = result.approved() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(response);
    }

    /** 按 reviewId 查询批量审查结果（含逐航线原因）。 */
    @GetMapping("/flight-reviews/{reviewId}")
    public FlightBatchReviewResult getFlightReview(@PathVariable String reviewId) {
        return service.getFlightReview(reviewId);
    }

    /** 查询航班详情（含跑道风险固化快照）。 */
    @GetMapping("/flights/{flightId}")
    public FlightDetailResult getFlight(@PathVariable String flightId) {
        return service.getFlight(flightId);
    }

    /** 查询航班最近一次审查原因。 */
    @GetMapping("/flights/{flightId}/review-reason")
    public FlightReviewReasonDto getFlightReviewReason(@PathVariable String flightId) {
        return service.getFlightReviewReason(flightId);
    }

    /** 起飞（仅 APPROVED 航班）。 */
    @PostMapping("/flights/depart")
    public ResponseEntity<MutationResponse> depart(@Valid @RequestBody FlightActionRequest request) {
        return ResponseEntity.ok(service.depart(request));
    }

    /** 取消航班。 */
    @PostMapping("/flights/cancel")
    public ResponseEntity<MutationResponse> cancel(@Valid @RequestBody FlightActionRequest request) {
        return ResponseEntity.ok(service.cancel(request));
    }

    /** 改航（仅 RUNWAY_RISK 航班，改航后回到 PENDING）。 */
    @PostMapping("/flights/reroute")
    public ResponseEntity<MutationResponse> reroute(
            @Valid @RequestBody FlightRerouteRequest request) {
        return ResponseEntity.ok(service.reroute(request));
    }

    /** 风险航班转为合格紧急例外（仅 RUNWAY_RISK 且全部风险窗口允许例外）。 */
    @PostMapping("/flights/emergency-convert")
    public ResponseEntity<MutationResponse> convertToEmergency(
            @Valid @RequestBody FlightEmergencyConvertRequest request) {
        return ResponseEntity.ok(service.convertToEmergency(request));
    }
}
