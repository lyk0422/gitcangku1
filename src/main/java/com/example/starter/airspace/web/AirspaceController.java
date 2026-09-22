package com.example.starter.airspace.web;

import com.example.starter.airspace.service.AirspaceService;
import com.example.starter.airspace.service.AirspaceService.CurrentView;
import com.example.starter.airspace.service.AirspaceService.OperationResult;
import com.example.starter.airspace.geom.Geometry.Point;
import com.example.starter.airspace.web.dto.ApiDtos.CurrentReviewResponse;
import com.example.starter.airspace.web.dto.ApiDtos.ReviewResponse;
import com.example.starter.airspace.web.dto.ApiDtos.RouteCreateRequest;
import com.example.starter.airspace.web.dto.ApiDtos.RouteReplaceRequest;
import com.example.starter.airspace.web.dto.ApiDtos.ReviewSubmitRequest;
import com.example.starter.airspace.web.dto.ApiDtos.ZoneCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 禁飞区域与航线版本审查 REST 接口。
 * 所有写操作必须携带全局唯一请求头 X-Request-Id 用于幂等。
 */
@RestController
@RequestMapping("/api/airspace")
@Validated
public class AirspaceController {

    private final AirspaceService service;

    public AirspaceController(AirspaceService service) {
        this.service = service;
    }

    /** 创建禁飞区。 */
    @PostMapping(value = "/zones/{zoneId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createZone(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable @Size(min = 1, max = 128) String zoneId,
            @Valid @RequestBody ZoneCreateRequest request) {
        return render(service.createZone(requestId, zoneId,
                request.xMin(), request.yMin(), request.xMax(), request.yMax()));
    }

    /** 撤销禁飞区。 */
    @PostMapping(value = "/zones/{zoneId}/revoke")
    public ResponseEntity<String> revokeZone(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable @Size(min = 1, max = 128) String zoneId) {
        return render(service.revokeZone(requestId, zoneId));
    }

    /** 创建航线（版本1）。 */
    @PostMapping(value = "/routes/{routeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createRoute(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable @Size(min = 1, max = 128) String routeId,
            @Valid @RequestBody RouteCreateRequest request) {
        return render(service.createRoute(requestId, routeId, toPoints(request.points())));
    }

    /** 替换航线点列，需 expectedVersion。 */
    @PostMapping(value = "/routes/{routeId}/replace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> replaceRoute(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable @Size(min = 1, max = 128) String routeId,
            @Valid @RequestBody RouteReplaceRequest request) {
        return render(service.replaceRoute(requestId, routeId,
                request.expectedVersion(), toPoints(request.points())));
    }

    /** 提交审核。 */
    @PostMapping(value = "/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submitReview(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ReviewSubmitRequest request) {
        return render(service.submitReview(requestId, request.routeId(),
                request.routeVersion(), request.airspaceVersion()));
    }

    /** 查询某航线全部历史审核结果（保留原结论）。 */
    @GetMapping(value = "/routes/{routeId}/reviews")
    public List<ReviewResponse> reviewHistory(
            @PathVariable @Size(min = 1, max = 128) String routeId) {
        return service.listReviewHistory(routeId);
    }

    /** 查询某航线当前可用结论：版本不匹配返回 STALE。 */
    @GetMapping(value = "/routes/{routeId}/reviews/current")
    public CurrentReviewResponse currentReview(
            @PathVariable @Size(min = 1, max = 128) String routeId) {
        CurrentView view = service.getCurrentReview(routeId);
        return new CurrentReviewResponse(view.status(), view.review());
    }

    private static List<Point> toPoints(
            List<com.example.starter.airspace.web.dto.ApiDtos.PointDto> dtos) {
        return dtos.stream().map(p -> new Point(p.x(), p.y())).toList();
    }

    private static ResponseEntity<String> render(OperationResult result) {
        return ResponseEntity.status(HttpStatus.valueOf(result.statusCode()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.rawBody());
    }
}
