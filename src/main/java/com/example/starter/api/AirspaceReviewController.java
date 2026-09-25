package com.example.starter.api;

import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyListResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.VerticalSeparationResult;
import com.example.starter.api.dto.ZoneBandsModifyRequest;
import com.example.starter.api.dto.ZoneBandsResult;
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

/**
 * 禁飞区域与航线版本审查 API，含高度带配置、高度层占用与垂直分离明细。
 */
@RestController
@RequestMapping("/api/airspace")
public class AirspaceReviewController {

    private final AirspaceReviewService service;

    public AirspaceReviewController(AirspaceReviewService service) {
        this.service = service;
    }

    /** 创建禁飞区（可同时登记初始高度带）。 */
    @PostMapping("/zones")
    public ResponseEntity<MutationResponse> createZone(@Valid @RequestBody ZoneCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createZone(request));
    }

    /** 撤销禁飞区。 */
    @PostMapping("/zones/revoke")
    public ResponseEntity<MutationResponse> revokeZone(@Valid @RequestBody ZoneRevokeRequest request) {
        return ResponseEntity.ok(service.revokeZone(request));
    }

    /** 修改区域高度带配置（只允许上调容量或新增不重叠带，携带配置 expectedVersion）。 */
    @PostMapping("/zones/bands")
    public ResponseEntity<MutationResponse> modifyZoneBands(
            @Valid @RequestBody ZoneBandsModifyRequest request) {
        return ResponseEntity.ok(service.modifyZoneBands(request));
    }

    /** 查询区域高度带配置。 */
    @GetMapping("/zones/{zoneId}/bands")
    public ZoneBandsResult getZoneBands(@PathVariable String zoneId) {
        return service.getZoneBands(zoneId);
    }

    /** 按时段查询区域占用记录（可选限定高度带，含已取消历史）。 */
    @GetMapping("/zones/{zoneId}/occupancies")
    public OccupancyListResult getOccupancies(@PathVariable String zoneId,
                                              @RequestParam(required = false) String bandId,
                                              @RequestParam(required = false) Long fromAt,
                                              @RequestParam(required = false) Long toAt) {
        return service.getOccupancies(zoneId, bandId, fromAt, toAt);
    }

    /** 创建航线。 */
    @PostMapping("/routes")
    public ResponseEntity<MutationResponse> createRoute(@Valid @RequestBody RouteCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRoute(request));
    }

    /** 替换航线点列与巡航参数。 */
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

    /** 航线垂直分离审查明细（基于审核快照与当前区域高度带）。 */
    @GetMapping("/reviews/{reviewId}/vertical-separation")
    public VerticalSeparationResult getVerticalSeparation(@PathVariable String reviewId) {
        return service.getVerticalSeparation(reviewId);
    }

    /** 创建高度层占用（关联 CLEAR 且未失效的审核；容量不足返回 429）。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupancy(request));
    }

    /** 取消高度层占用（立即释放容量，历史保留）。 */
    @PostMapping("/occupancies/cancel")
    public ResponseEntity<MutationResponse> cancelOccupancy(
            @Valid @RequestBody OccupancyCancelRequest request) {
        return ResponseEntity.ok(service.cancelOccupancy(request));
    }
}
