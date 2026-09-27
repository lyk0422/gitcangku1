package com.example.starter.baggage;

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

import com.example.starter.baggage.DwellDtos.DwellCheckRequest;
import com.example.starter.baggage.DwellDtos.DwellCheckResponse;
import com.example.starter.baggage.DwellDtos.DwellDetailResponse;
import com.example.starter.baggage.DwellDtos.DwellDiagnosticsResponse;
import com.example.starter.baggage.DwellDtos.DwellReleaseRequest;
import com.example.starter.baggage.DwellDtos.DwellReleaseResponse;
import com.example.starter.baggage.DwellDtos.LegConstraintConfigRequest;
import com.example.starter.baggage.DwellDtos.LegConstraintResponse;
import com.example.starter.baggage.DwellDtos.ReassignRequest;
import com.example.starter.baggage.DwellDtos.ReassignResponse;
import com.example.starter.baggage.DwellDtos.TransitArrivalRequest;
import com.example.starter.baggage.DwellDtos.TransitArrivalResponse;
import com.example.starter.baggage.DwellDtos.TransitPointConfigRequest;
import com.example.starter.baggage.DwellDtos.TransitPointConfigResponse;

/**
 * 行李滞留监控 REST 入口：中转点配置、航段约束、到达核对、超时裁决、解除阻断与改派、查询。
 */
@RestController
@RequestMapping("/api/dwell")
public class DwellController {

    private final DwellService dwellService;

    public DwellController(DwellService dwellService) {
        this.dwellService = dwellService;
    }

    /** 配置中转点最大滞留分钟数。 */
    @PostMapping("/transit-points")
    public ResponseEntity<TransitPointConfigResponse> configureTransitPoint(
            @Valid @RequestBody TransitPointConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dwellService.configureTransitPoint(request));
    }

    /** 配置航段容量与截单时刻（容量/截止可分别为 null 表示不限制）。 */
    @PostMapping("/leg-constraints")
    public ResponseEntity<LegConstraintResponse> configureLegConstraint(
            @Valid @RequestBody LegConstraintConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dwellService.configureLegConstraint(request));
    }

    /** 批量到达核对：任一行李状态非法整次回滚。 */
    @PostMapping("/transit-arrivals")
    public TransitArrivalResponse arriveAtTransitPoint(@Valid @RequestBody TransitArrivalRequest request) {
        return dwellService.arriveAtTransitPoint(request);
    }

    /** 滞留超时裁决：bagTags 缺省/空表示裁决该中转点全部在滞行李。 */
    @PostMapping("/check")
    public DwellCheckResponse checkDwell(@Valid @RequestBody DwellCheckRequest request) {
        return dwellService.checkDwell(request);
    }

    /** 复核人解除阻断：必须指定下一航段并重新校验截单与容量。 */
    @PostMapping("/release")
    public DwellReleaseResponse release(@Valid @RequestBody DwellReleaseRequest request) {
        return dwellService.release(request);
    }

    /** 改派下一航段：追加不可变记录，不改写超时证据。 */
    @PostMapping("/reassign")
    public ReassignResponse reassign(@Valid @RequestBody ReassignRequest request) {
        return dwellService.reassign(request);
    }

    /** 行李滞留明细：当前开放周期与不可变历史。 */
    @GetMapping("/bags/{bagTag}")
    public DwellDetailResponse getDetail(@PathVariable String bagTag) {
        return dwellService.getDetail(bagTag);
    }

    /** 滞留诊断查询：可按 stationCode 过滤，缺省查全部，按 (station, bagTag) 稳定排序。 */
    @GetMapping("/diagnostics")
    public DwellDiagnosticsResponse getDiagnostics(
            @RequestParam(name = "stationCode", required = false) String stationCode) {
        return dwellService.getDiagnostics(stationCode);
    }
}
