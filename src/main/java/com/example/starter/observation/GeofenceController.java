package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站点矩形地理围栏 API：创建、变更（追加新版本）、当前/历史版本与站点围栏列表查询。
 */
@RestController
@Validated
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    /**
     * 创建站点矩形围栏，初始版本为 1。
     */
    @PostMapping("/api/geofences")
    public ResponseEntity<GeofenceResponse> create(@Valid @RequestBody CreateGeofenceRequest request) {
        GeofenceService.GeofenceOutcome outcome = geofenceService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 变更围栏：expectedVersion 匹配当前版本时追加新版本；历史版本保留，不重算历史匹配结果。
     */
    @PostMapping("/api/geofences/{fenceId}/updates")
    public ResponseEntity<GeofenceResponse> update(@PathVariable String fenceId,
                                                   @Valid @RequestBody UpdateGeofenceRequest request) {
        GeofenceService.GeofenceOutcome outcome = geofenceService.update(fenceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询围栏当前版本。
     */
    @GetMapping("/api/geofences/{fenceId}")
    public GeofenceResponse getCurrent(@PathVariable String fenceId) {
        return geofenceService.getCurrent(fenceId);
    }

    /**
     * 查询围栏指定历史版本。
     */
    @GetMapping("/api/geofences/{fenceId}/versions/{fenceVersion}")
    public GeofenceResponse getVersion(@PathVariable String fenceId,
                                       @PathVariable @Min(1) int fenceVersion) {
        return geofenceService.getVersion(fenceId, fenceVersion);
    }

    /**
     * 查询站点全部围栏的当前版本，按围栏标识稳定排序。
     */
    @GetMapping("/api/sites/{siteId}/geofences")
    public List<GeofenceResponse> listBySite(@PathVariable String siteId) {
        return geofenceService.listCurrentBySite(siteId);
    }
}
