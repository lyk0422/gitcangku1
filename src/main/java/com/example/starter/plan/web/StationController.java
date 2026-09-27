package com.example.starter.plan.web;

import com.example.starter.plan.service.StationService;
import com.example.starter.plan.web.dto.StationConfigRequest;
import com.example.starter.plan.web.dto.StationConfigResponse;
import com.example.starter.plan.web.dto.StationDiagnosticsResponse;
import com.example.starter.plan.web.dto.StationSnapshotView;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中间站站台配置与站停查询 API：配置登记/修订（版本裁决 + 幂等）、
 * 当前配置、发布快照历史与站台容量诊断。所有 GET 接口只读。
 */
@Validated
@RestController
@RequestMapping("/api/v1/stations")
public class StationController {

    private final StationService service;

    public StationController(StationService service) {
        this.service = service;
    }

    /**
     * 登记新站点（不带 expectedVersion）或修订配置（expectedVersion 匹配当前版本）。
     */
    @PutMapping("/{stationId}/config")
    @ResponseStatus(HttpStatus.OK)
    public StationConfigResponse saveConfig(@PathVariable String stationId,
                                            @Valid @RequestBody StationConfigRequest request) {
        return service.saveConfig(stationId, request);
    }

    /**
     * 查询站点当前配置。
     */
    @GetMapping("/{stationId}/config")
    public StationConfigResponse getConfig(@PathVariable String stationId) {
        return service.getConfig(stationId);
    }

    /**
     * 查询站点发布快照历史（含已取消计划的历史快照），可按运营日过滤；排序稳定，只读。
     */
    @GetMapping("/{stationId}/snapshots")
    public List<StationSnapshotView> getSnapshots(
            @PathVariable String stationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getSnapshotHistory(stationId, date);
    }

    /**
     * 站台容量诊断：实际峰值、容量要求、差额与生效快照明细，只读。
     */
    @GetMapping("/{stationId}/diagnostics")
    public StationDiagnosticsResponse getDiagnostics(
            @PathVariable String stationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getDiagnostics(stationId, date);
    }
}
