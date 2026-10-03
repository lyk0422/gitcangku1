package com.example.starter.api;

import com.example.starter.api.dto.BatchReviewRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReviewDiagnosticsDto;
import com.example.starter.api.dto.WeatherForecastDetailDto;
import com.example.starter.api.dto.WeatherForecastUploadRequest;
import com.example.starter.api.dto.WeatherMinimaConfigRequest;
import com.example.starter.api.dto.WeatherMinimaDetailDto;
import com.example.starter.service.AirspaceReviewService;
import com.example.starter.service.WeatherService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 空域气象最低标准、预测气象、批量审查与占用 API。
 * 所有查询端点只读，不改变状态。
 */
@RestController
@RequestMapping("/api/airspace")
public class WeatherController {

    private final WeatherService weatherService;
    private final AirspaceReviewService reviewService;

    public WeatherController(WeatherService weatherService, AirspaceReviewService reviewService) {
        this.weatherService = weatherService;
        this.reviewService = reviewService;
    }

    /** 整体替换某区域的气象最低标准时段集合。 */
    @PostMapping("/weather/minima")
    public ResponseEntity<MutationResponse> configureMinima(
            @Valid @RequestBody WeatherMinimaConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(weatherService.configureMinima(request));
    }

    /** 整批上传预测气象，生效为一个新的气象版本。 */
    @PostMapping("/weather/forecasts")
    public ResponseEntity<MutationResponse> uploadForecast(
            @Valid @RequestBody WeatherForecastUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(weatherService.uploadForecast(request));
    }

    /** 查询当前全局气象（值班）版本。 */
    @GetMapping("/weather/version")
    public Map<String, Object> getWeatherVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weatherVersion", weatherService.getWeatherVersion());
        return body;
    }

    /** 查询某区域当前生效的标准时段明细。 */
    @GetMapping("/weather/minima/{zoneId}")
    public WeatherMinimaDetailDto getMinimaDetail(@PathVariable String zoneId) {
        return weatherService.getMinimaDetail(zoneId);
    }

    /** 查询截至指定版本可见的预测明细。 */
    @GetMapping("/weather/forecasts/{version}")
    public WeatherForecastDetailDto getForecastDetail(@PathVariable long version) {
        return weatherService.getForecastDetail(version);
    }

    /** 批量审查：任一航线校验失败则整批不创建结果。 */
    @PostMapping("/reviews/batch")
    public ResponseEntity<MutationResponse> reviewBatch(
            @Valid @RequestBody BatchReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.reviewBatch(request));
    }

    /** 依据未失效的 CLEAR 审查创建占用。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(weatherService.createOccupancy(request));
    }

    /** 按 occupancyId 查询占用明细。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyResult getOccupancy(@PathVariable String occupancyId) {
        return weatherService.getOccupancy(occupancyId);
    }

    /** 审查时效诊断：保留原结论，逐项对比固化版本与当前版本。 */
    @GetMapping("/reviews/{reviewId}/diagnostics")
    public ReviewDiagnosticsDto getDiagnostics(@PathVariable String reviewId) {
        return weatherService.getDiagnostics(reviewId);
    }
}