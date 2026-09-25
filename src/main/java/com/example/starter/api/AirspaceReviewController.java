package com.example.starter.api;

import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.ClosureCreateRequest;
import com.example.starter.api.dto.EmergencyExceptionRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteRiskResult;
import com.example.starter.api.dto.RouteStateRequest;
import com.example.starter.api.dto.RunwayClosuresResult;
import com.example.starter.api.dto.RunwayCreateRequest;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.RunwayClosureService;
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
 * 禁飞区域与航线版本审查 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class AirspaceReviewController {

    private final AirspaceReviewService service;
    private final RunwayClosureService runwayClosureService;

    public AirspaceReviewController(AirspaceReviewService service,
                                    RunwayClosureService runwayClosureService) {
        this.service = service;
        this.runwayClosureService = runwayClosureService;
    }

    /** 创建禁飞区。 */
    @PostMapping("/zones")
    public ResponseEntity<MutationResponse> createZone(@Valid @RequestBody ZoneCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createZone(request));
    }

    /** 撤销禁飞区。 */
    @PostMapping("/zones/revoke")
    public ResponseEntity<MutationResponse> revokeZone(@Valid @RequestBody ZoneRevokeRequest request) {
        return ResponseEntity.ok(service.revokeZone(request));
    }

    /** 创建航线。 */
    @PostMapping("/routes")
    public ResponseEntity<MutationResponse> createRoute(@Valid @RequestBody RouteCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRoute(request));
    }

    /** 替换航线点列。 */
    @PostMapping("/routes/replace")
    public ResponseEntity<MutationResponse> replaceRoute(@Valid @RequestBody RouteReplaceRequest request) {
        return ResponseEntity.ok(service.replaceRoute(request));
    }

    /** 提交审核。 */
    @PostMapping("/reviews")
    public ResponseEntity<MutationResponse> review(@Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.review(request));
    }

    /** 批量审核：任一航线被拒绝则整批不批准。 */
    @PostMapping("/reviews/batch")
    public ResponseEntity<MutationResponse> reviewBatch(
            @Valid @RequestBody BatchReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reviewBatch(request));
    }

    /** 登记跑道。 */
    @PostMapping("/runways")
    public ResponseEntity<MutationResponse> createRunway(
            @Valid @RequestBody RunwayCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(runwayClosureService.createRunway(request));
    }

    /** 登记跑道关闭窗口（携带跑道版本；命中未来已批准 NORMAL 航线转为风险）。 */
    @PostMapping("/runways/closures")
    public ResponseEntity<MutationResponse> createClosure(
            @Valid @RequestBody ClosureCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(runwayClosureService.createClosure(request));
    }

    /** 查询跑道全部关闭窗口与当前版本。 */
    @GetMapping("/runways/{runwayId}/closures")
    public RunwayClosuresResult getRunwayClosures(@PathVariable String runwayId) {
        return runwayClosureService.getRunwayClosures(runwayId);
    }

    /** 航线起飞（仅已批准航线）。 */
    @PostMapping("/routes/depart")
    public ResponseEntity<MutationResponse> depart(@Valid @RequestBody RouteStateRequest request) {
        return ResponseEntity.ok(runwayClosureService.depart(request));
    }

    /** 取消航线。 */
    @PostMapping("/routes/cancel")
    public ResponseEntity<MutationResponse> cancel(@Valid @RequestBody RouteStateRequest request) {
        return ResponseEntity.ok(runwayClosureService.cancel(request));
    }

    /** 风险航线转合格紧急例外。 */
    @PostMapping("/routes/emergency-exception")
    public ResponseEntity<MutationResponse> convertToEmergencyException(
            @Valid @RequestBody EmergencyExceptionRequest request) {
        return ResponseEntity.ok(runwayClosureService.convertToEmergencyException(request));
    }

    /** 查询航线跑道风险快照。 */
    @GetMapping("/routes/{routeId}/risk")
    public RouteRiskResult getRouteRisk(@PathVariable String routeId) {
        return runwayClosureService.getRouteRisk(routeId);
    }

    /** 按 reviewId 查询历史审核结果（保留原结论）。 */
    @GetMapping("/reviews/{reviewId}")
    public ReviewResultDto getReview(@PathVariable String reviewId) {
        return service.getReview(reviewId);
    }

    /** 查询某航线当前可用结论（版本不匹配返回 STALE）。 */
    @GetMapping("/routes/{routeId}/current-review")
    public ReviewResultDto getCurrentReview(@PathVariable String routeId) {
        return service.getCurrentReview(routeId);
    }
}
