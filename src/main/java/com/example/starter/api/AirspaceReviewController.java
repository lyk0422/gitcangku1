package com.example.starter.api;

import com.example.starter.api.dto.BandConfigureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCancelRequest;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResultDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.VerticalZoneDto;
import com.example.starter.api.dto.ZoneBandsView;
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
 * 禁飞区域、航线版本审查、高度带配置与高度层占用 API。
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

    /** 配置区域高度带（仅扩容或新增不重叠带；携带区域 expectedVersion）。 */
    @PostMapping("/zones/bands")
    public ResponseEntity<MutationResponse> configureBands(
            @Valid @RequestBody BandConfigureRequest request) {
        return ResponseEntity.ok(service.configureBands(request));
    }

    /** 查询区域高度带配置。 */
    @GetMapping("/zones/{zoneId}/bands")
    public ZoneBandsView getZoneBands(@PathVariable String zoneId) {
        return service.getZoneBands(zoneId);
    }

    /** 创建航线。 */
    @PostMapping("/routes")
    public ResponseEntity<MutationResponse> createRoute(@Valid @RequestBody RouteCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRoute(request));
    }

    /** 替换航线点列与飞行剖面。 */
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

    /** 查询某审核的航线垂直分离审查明细。 */
    @GetMapping("/reviews/{reviewId}/vertical-separation")
    public List<VerticalZoneDto> getVerticalSeparation(@PathVariable String reviewId) {
        return service.getVerticalSeparation(reviewId);
    }

    /** 查询某航线当前可用结论（版本不匹配返回 STALE）。 */
    @GetMapping("/routes/{routeId}/current-review")
    public ReviewResultDto getCurrentReview(@PathVariable String routeId) {
        return service.getCurrentReview(routeId);
    }

    /** 创建高度层占用（仅当前仍 CLEAR 的审查）。 */
    @PostMapping("/occupations")
    public ResponseEntity<MutationResponse> createOccupation(
            @Valid @RequestBody OccupationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupation(request));
    }

    /** 取消高度层占用（立即释放容量，历史保留）。 */
    @PostMapping("/occupations/cancel")
    public ResponseEntity<MutationResponse> cancelOccupation(
            @Valid @RequestBody OccupationCancelRequest request) {
        return ResponseEntity.ok(service.cancelOccupation(request));
    }

    /** 按时段查询占用（zoneId 可选），含已取消历史。 */
    @GetMapping("/occupations")
    public List<OccupationResultDto> listOccupations(
            @RequestParam(required = false) String zoneId,
            @RequestParam long fromUtc,
            @RequestParam long toUtc) {
        return service.listOccupations(zoneId, fromUtc, toUtc);
    }
}
