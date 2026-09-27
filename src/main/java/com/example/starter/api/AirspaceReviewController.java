package com.example.starter.api;

import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.DutyAdvanceRequest;
import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastView;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaResult;
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

    /** 配置/修订区域气象最低标准（整组替换，推进气象标准版本）。 */
    @PostMapping("/weather/minima")
    public ResponseEntity<MutationResponse> configureMinima(
            @Valid @RequestBody WeatherMinimaConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.configureMinima(request));
    }

    /** 查询某区域当前生效的气象最低标准。 */
    @GetMapping("/weather/minima/{zoneId}")
    public WeatherMinimaResult getMinima(@PathVariable String zoneId) {
        return service.getMinima(zoneId);
    }

    /** 上传预测气象（整批生效，分配新预测版本）。 */
    @PostMapping("/weather/forecasts")
    public ResponseEntity<MutationResponse> uploadForecast(
            @Valid @RequestBody ForecastUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadForecast(request));
    }

    /** 按版本查询预测明细。 */
    @GetMapping("/weather/forecasts/{forecastVersion}")
    public ForecastView getForecast(@PathVariable long forecastVersion) {
        return service.getForecast(forecastVersion);
    }

    /** 值班换班（推进值班版本）。 */
    @PostMapping("/duty/advance")
    public ResponseEntity<MutationResponse> advanceDuty(@Valid @RequestBody DutyAdvanceRequest request) {
        return ResponseEntity.ok(service.advanceDuty(request));
    }

    /** 提交审核。 */
    @PostMapping("/reviews")
    public ResponseEntity<MutationResponse> review(@Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.review(request));
    }

    /** 批量审核（整批事务：任一航线缺预测或版本不符则整批不创建）。 */
    @PostMapping("/reviews/batch")
    public ResponseEntity<MutationResponse> reviewBatch(@Valid @RequestBody BatchReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reviewBatch(request));
    }

    /** 按 reviewId 查询历史审核结果（保留原结论）。 */
    @GetMapping("/reviews/{reviewId}")
    public ReviewResultDto getReview(@PathVariable String reviewId) {
        return service.getReview(reviewId);
    }

    /** 查询审核时效诊断（逐维度对比固化版本与当前版本）。 */
    @GetMapping("/reviews/{reviewId}/diagnostics")
    public ReviewDiagnosticsDto getReviewDiagnostics(@PathVariable String reviewId) {
        return service.getReviewDiagnostics(reviewId);
    }

    /** 查询某航线当前可用结论（版本不匹配返回 STALE）。 */
    @GetMapping("/routes/{routeId}/current-review")
    public ReviewResultDto getCurrentReview(@PathVariable String routeId) {
        return service.getCurrentReview(routeId);
    }

    /** 查询某航线的全部审核历史（稳定排序）。 */
    @GetMapping("/routes/{routeId}/reviews")
    public List<ReviewResultDto> getReviewHistory(@PathVariable String routeId) {
        return service.getReviewHistory(routeId);
    }

    /** 基于 CLEAR 且未失效的审核结果创建空域占用。 */
    @PostMapping("/occupations")
    public ResponseEntity<MutationResponse> createOccupation(
            @Valid @RequestBody OccupationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupation(request));
    }

    /** 按 occupationId 查询占用明细。 */
    @GetMapping("/occupations/{occupationId}")
    public OccupationResult getOccupation(@PathVariable String occupationId) {
        return service.getOccupation(occupationId);
    }

    /** 查询某航线的全部占用历史（稳定排序）。 */
    @GetMapping("/routes/{routeId}/occupations")
    public List<OccupationResult> getOccupationsByRoute(@PathVariable String routeId) {
        return service.getOccupationsByRoute(routeId);
    }
}
