package com.example.starter.api;

import com.example.starter.api.dto.ForecastUploadRequest;
import com.example.starter.api.dto.ForecastVersionDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationResult;
import com.example.starter.api.dto.ReviewBatchRequest;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.WeatherMinimaResult;
import com.example.starter.api.dto.WeatherMinimaSetRequest;
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
 * 禁飞区域、航线版本审查与空域气象最低标准 API。
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

    /** 批量审核：任一航线缺预测或任一校验失败，整批不创建结果。 */
    @PostMapping("/reviews/batch")
    public ResponseEntity<MutationResponse> reviewBatch(@Valid @RequestBody ReviewBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reviewBatch(request));
    }

    /** 按 reviewId 查询历史审核结果（保留原结论）。 */
    @GetMapping("/reviews/{reviewId}")
    public ReviewResultDto getReview(@PathVariable String reviewId) {
        return service.getReview(reviewId);
    }

    /** 审核时效诊断：逐项比对固化版本与当前版本。 */
    @GetMapping("/reviews/{reviewId}/diagnostics")
    public ReviewDiagnosticsDto getReviewDiagnostics(@PathVariable String reviewId) {
        return service.getReviewDiagnostics(reviewId);
    }

    /** 查询某航线当前可用结论（版本不匹配返回 STALE）。 */
    @GetMapping("/routes/{routeId}/current-review")
    public ReviewResultDto getCurrentReview(@PathVariable String routeId) {
        return service.getCurrentReview(routeId);
    }

    /** 查询某航线全部历史审核（稳定排序，保留原结论）。 */
    @GetMapping("/routes/{routeId}/reviews")
    public List<ReviewResultDto> getRouteReviews(@PathVariable String routeId) {
        return service.getRouteReviews(routeId);
    }

    /** 整体替换某区域的气象最低标准（负值/时段重叠返回 422）。 */
    @PostMapping("/weather/minima")
    public ResponseEntity<MutationResponse> setWeatherMinima(
            @Valid @RequestBody WeatherMinimaSetRequest request) {
        return ResponseEntity.ok(service.setWeatherMinima(request));
    }

    /** 查询某区域当前生效的气象最低标准。 */
    @GetMapping("/zones/{zoneId}/weather-minima")
    public WeatherMinimaResult getWeatherMinima(@PathVariable String zoneId) {
        return service.getWeatherMinima(zoneId);
    }

    /** 上传预测气象，生成新的预测版本。 */
    @PostMapping("/weather/forecasts")
    public ResponseEntity<MutationResponse> uploadForecast(
            @Valid @RequestBody ForecastUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadForecast(request));
    }

    /** 查询当前最新预测版本明细。 */
    @GetMapping("/weather/forecasts/current")
    public ForecastVersionDto getCurrentForecast() {
        return service.getCurrentForecast();
    }

    /** 查询指定预测版本明细。 */
    @GetMapping("/weather/forecasts/{version}")
    public ForecastVersionDto getForecast(@PathVariable long version) {
        return service.getForecast(version);
    }

    /** 基于未失效的 CLEAR 审核结果创建占用（固化审核时气象版本）。 */
    @PostMapping("/occupations")
    public ResponseEntity<MutationResponse> createOccupation(
            @Valid @RequestBody OccupationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupation(request));
    }

    /** 按 occupationId 查询占用记录。 */
    @GetMapping("/occupations/{occupationId}")
    public OccupationResult getOccupation(@PathVariable String occupationId) {
        return service.getOccupation(occupationId);
    }

    /** 查询某航线全部占用记录（稳定排序）。 */
    @GetMapping("/routes/{routeId}/occupations")
    public List<OccupationResult> getRouteOccupations(@PathVariable String routeId) {
        return service.getRouteOccupations(routeId);
    }
}
