package com.example.starter.station.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.StationDiagnosticsResponse;
import com.example.starter.station.service.StationService;
import com.example.starter.station.web.dto.StationConfigRequest;
import com.example.starter.station.web.dto.StationConfigView;
import com.example.starter.station.web.dto.StationHistoryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
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
 * 站台配置 API：创建/修订（幂等）、当前配置查询、不可变版本历史与站台占用诊断。
 */
@Validated
@RestController
@RequestMapping("/api/v1/stations")
public class StationController {

    private final StationService service;
    private final PlanService planService;

    public StationController(StationService service, PlanService planService) {
        this.service = service;
        this.planService = planService;
    }

    /**
     * 创建或修订站台最小停站分钟数与站台容量；不存在则创建版本 1，存在则版本加一。
     */
    @PutMapping("/{stationId}/config")
    @ResponseStatus(HttpStatus.OK)
    public StationConfigView upsertConfig(@PathVariable String stationId,
                                          @Valid @RequestBody StationConfigRequest request) {
        return service.upsertConfig(stationId, request);
    }

    /**
     * 查询站台当前配置。
     */
    @GetMapping("/{stationId}/config")
    public StationConfigView getConfig(@PathVariable String stationId) {
        return service.getConfig(stationId);
    }

    /**
     * 查询站台配置不可变版本历史，按版本号升序。
     */
    @GetMapping("/{stationId}/history")
    public StationHistoryResponse getHistory(@PathVariable String stationId) {
        return service.getHistory(stationId);
    }

    /**
     * 站台占用诊断：运营日生效站停明细、实际峰值并行停靠数、容量要求与差额，稳定排序。
     */
    @GetMapping("/{stationId}/diagnostics")
    public StationDiagnosticsResponse getDiagnostics(
            @PathVariable String stationId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return planService.getStationDiagnostics(date, stationId);
    }
}
