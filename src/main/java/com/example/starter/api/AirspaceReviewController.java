package com.example.starter.api;

import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.ForecastDetailDto;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.WeatherRegionConfigRequest;
import com.example.starter.api.dto.WeatherRegionDetailDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 禁飞区域、气象最低标准与航线版本审查 API。
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

    /** 批量审核：任一航线缺预测或校验失败则整批不创建结果。 */
    @PostMapping("/reviews/batch")
    public ResponseEntity<MutationResponse> reviewBatch(@Valid @RequestBody BatchReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reviewBatch(request));
    }

    /** 配置（或修订）气象区域最低标准。 */
    @PostMapping("/weather/regions")
    public ResponseEntity<MutationResponse> configureWeatherRegion(
            @Valid @RequestBody WeatherRegionConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.configureWeatherRegion(request));
    }

    /** 上传预测气象（整体固化为新版本）。 */
    @PostMapping("/weather/forecasts")
    public ResponseEntity<MutationResponse> uploadForecast(
            @Valid @RequestBody ForecastUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadForecast(request));
    }

    /** 创建空域占用（必须使用审核时固化的气象版本，STALE 审核拒绝）。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupancy(request));
    }

    /** 按 reviewId 查询历史审核结果（保留原结论）。 */
    @GetMapping("/reviews/{reviewId}")
    public ReviewResultDto getReview(@PathVariable String reviewId) {
        return service.getReview(reviewId);
    }

    /** 审核时效诊断：固化版本与当前版本对照及失效原因（只读）。 */
    @GetMapping("/reviews/{reviewId}/diagnostics")
    public ReviewDiagnosticsDto getReviewDiagnostics(@PathVariable String reviewId) {
        return service.getReviewDiagnostics(reviewId);
    }

    /** 查询某航线当前可用结论（版本不匹配返回 STALE）。 */
    @GetMapping("/routes/{routeId}/current-review")
    public ReviewResultDto getCurrentReview(@PathVariable String routeId) {
        return service.getCurrentReview(routeId);
    }

    /** 查询某航线全部审核历史（倒序，排序稳定，只读）。 */
    @GetMapping("/routes/{routeId}/reviews")
    public List<ReviewResultDto> getRouteReviewHistory(@PathVariable String routeId) {
        return service.getRouteReviewHistory(routeId);
    }

    /** 查询气象区域明细（含当前最低标准窗口）。 */
    @GetMapping("/weather/regions/{regionId}")
    public WeatherRegionDetailDto getWeatherRegion(@PathVariable String regionId) {
        return service.getWeatherRegion(regionId);
    }

    /** 查询某预测气象版本明细。 */
    @GetMapping("/weather/forecasts/{version}")
    public ForecastDetailDto getForecast(@PathVariable long version) {
        return service.getForecast(version);
    }

    /** 查询占用记录。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyResult getOccupancy(@PathVariable String occupancyId) {
        return service.getOccupancy(occupancyId);
    }
}