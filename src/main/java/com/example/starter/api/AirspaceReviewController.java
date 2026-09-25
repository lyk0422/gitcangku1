package com.example.starter.api;

import com.example.starter.api.dto.CapacityBucketDto;
import com.example.starter.api.dto.CapacityBucketRequest;
import com.example.starter.api.dto.DepartRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PreemptionDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteStateResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.service.AirspaceReviewService;
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

import java.util.List;

/**
 * 禁飞区域与航线版本审查 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class AirspaceReviewController {

    private final AirspaceReviewService service;

    public AirspaceReviewController(AirspaceReviewService service) {
        this.service = service;
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

    /** 起飞登记（仅 APPROVED 航线）。 */
    @PostMapping("/routes/depart")
    public ResponseEntity<MutationResponse> depart(@Valid @RequestBody DepartRequest request) {
        return ResponseEntity.ok(service.depart(request));
    }

    /** 创建时空容量桶。 */
    @PostMapping("/capacity-buckets")
    public ResponseEntity<MutationResponse> createCapacityBucket(
            @Valid @RequestBody CapacityBucketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCapacityBucket(request));
    }

    /** 查询全部容量桶及占用。 */
    @GetMapping("/capacity-buckets")
    public List<CapacityBucketDto> listCapacityBuckets() {
        return service.listCapacityBuckets();
    }

    /** 查询抢占记录（可按 routeId、state 过滤）。 */
    @GetMapping("/preemptions")
    public List<PreemptionDto> listPreemptions(
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String state) {
        return service.listPreemptions(routeId, state);
    }

    /** 查询全部被置换（DISPLACED）航线。 */
    @GetMapping("/routes/displaced")
    public List<RouteStateResult> listDisplacedRoutes() {
        return service.listDisplacedRoutes();
    }
}
