package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测地理围栏 API：创建、改版、当前配置、版本历史与站点诊断查询。
 */
@RestController
@RequestMapping("/api/geofences")
@Validated
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    /**
     * 创建矩形地理围栏，初始配置版本为 1。
     */
    @PostMapping
    public ResponseEntity<GeofenceResponse> create(@Valid @RequestBody CreateGeofenceRequest request) {
        GeofenceService.GeofenceOutcome outcome = geofenceService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 围栏改版：追加不可变配置版本。
     */
    @PostMapping("/{fenceId}/revisions")
    public ResponseEntity<GeofenceResponse> revise(@PathVariable String fenceId,
                                                   @Valid @RequestBody ReviseGeofenceRequest request) {
        GeofenceService.GeofenceOutcome outcome = geofenceService.revise(fenceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询围栏当前生效配置版本。
     */
    @GetMapping("/{fenceId}")
    public GeofenceResponse getCurrent(@PathVariable String fenceId) {
        return geofenceService.getCurrent(fenceId);
    }

    /**
     * 查询围栏全部配置版本，按版本号升序。
     */
    @GetMapping("/{fenceId}/versions")
    public List<GeofenceResponse> listVersions(@PathVariable String fenceId) {
        return geofenceService.listVersions(fenceId).stream()
                .map(GeofenceResponse::of)
                .toList();
    }

    /**
     * 诊断查询：按站点列出全部围栏的当前生效配置，按 fenceId 升序。
     */
    @GetMapping
    public List<GeofenceResponse> listBySite(@RequestParam String siteId) {
        return geofenceService.listSiteCurrent(siteId).stream()
                .map(GeofenceResponse::of)
                .toList();
    }
}
