package com.example.starter.baggage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ArriveResponse;
import com.example.starter.baggage.BaggageDtos.BagResponse;
import com.example.starter.baggage.BaggageDtos.ConfigureDwellLimitRequest;
import com.example.starter.baggage.BaggageDtos.ConfigureLegOpsRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.DwellConfigResponse;
import com.example.starter.baggage.BaggageDtos.DwellHistoryResponse;
import com.example.starter.baggage.BaggageDtos.DwellRecordResponse;
import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckRequest;
import com.example.starter.baggage.BaggageDtos.DwellTimeoutCheckResponse;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegOpsConfigResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReleaseDwellRequest;
import com.example.starter.baggage.BaggageDtos.ReleaseDwellResponse;
import com.example.starter.baggage.BaggageDtos.RerouteRequest;
import com.example.starter.baggage.BaggageDtos.RerouteResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.StationDwellDiagnostics;
import com.example.starter.baggage.BaggageDtos.TransitArriveRequest;
import com.example.starter.baggage.BaggageDtos.TransitArriveResponse;

/**
 * 联程行李装载交接 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class BaggageController {

    private final BaggageService baggageService;

    public BaggageController(BaggageService baggageService) {
        this.baggageService = baggageService;
    }

    /** 登记航段。 */
    @PostMapping("/legs")
    public ResponseEntity<LegResponse> registerLeg(@Valid @RequestBody RegisterLegRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(baggageService.registerLeg(request));
    }

    /** 登记行李及其有序行程。 */
    @PostMapping("/bags")
    public ResponseEntity<BagResponse> registerBag(@Valid @RequestBody RegisterBagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(baggageService.registerBag(request));
    }

    /** 批量装载：整批原子，任一行李不满足则 422 且无一件移动。 */
    @PostMapping("/legs/{legId}/load")
    public LoadResponse load(@PathVariable String legId, @Valid @RequestBody LoadRequest request) {
        return baggageService.load(legId, request);
    }

    /** 封舱：校验版本，保存只读装载清单并转 SEALED。 */
    @PostMapping("/legs/{legId}/seal")
    public SealResponse seal(@PathVariable String legId, @Valid @RequestBody SealRequest request) {
        return baggageService.seal(legId, request);
    }

    /** 到达确认：实际袋号集合须与封舱清单完全一致。 */
    @PostMapping("/legs/{legId}/arrive")
    public ArriveResponse arrive(@PathVariable String legId, @Valid @RequestBody ArriveRequest request) {
        return baggageService.arrive(legId, request);
    }

    /** 差异到达：实际袋号集合须为封舱清单子集，缺失行李转短卸。 */
    @PostMapping("/legs/{legId}/arrive-difference")
    public DifferenceArriveResponse arriveDifference(@PathVariable String legId,
                                                     @Valid @RequestBody DifferenceArriveRequest request) {
        return baggageService.arriveDifference(legId, request);
    }

    /** 补到：短卸行李在缺失航段的应到站实际到达后恢复行程。 */
    @PostMapping("/bags/recover")
    public RecoverResponse recover(@Valid @RequestBody RecoverRequest request) {
        return baggageService.recover(request);
    }

    /** 行李完整轨迹查询。 */
    @GetMapping("/bags/{bagTag}/trace")
    public BagResponse getBagTrace(@PathVariable String bagTag) {
        return baggageService.getBagTrace(bagTag);
    }

    /** 封舱清单查询。 */
    @GetMapping("/legs/{legId}/manifest")
    public ManifestResponse getManifest(@PathVariable String legId) {
        return baggageService.getManifest(legId);
    }

    /** 航段差异快照查询：返回只读封舱清单与差异到达实际集合。 */
    @GetMapping("/legs/{legId}/difference")
    public LegDifferenceResponse getDifference(@PathVariable String legId) {
        return baggageService.getDifference(legId);
    }

    /** 未补到短卸行李清单查询。 */
    @GetMapping("/bags/short-unloaded")
    public ShortListResponse listShortUnloaded() {
        return baggageService.listShortUnloaded();
    }

    /** 配置中转点最大滞留分钟数。 */
    @PostMapping("/stations/{station}/dwell-config")
    public DwellConfigResponse configureDwellLimit(@PathVariable String station,
                                                   @Valid @RequestBody ConfigureDwellLimitRequest request) {
        return baggageService.configureDwellLimit(station, request);
    }

    /** 配置航段装载截止时刻与最大容量。 */
    @PostMapping("/legs/{legId}/ops-config")
    public LegOpsConfigResponse configureLegOps(@PathVariable String legId,
                                                @Valid @RequestBody ConfigureLegOpsRequest request) {
        return baggageService.configureLegOps(legId, request);
    }

    /** 中转到达核对：批量登记行李到达中转点，任一行李非法整次回滚。 */
    @PostMapping("/stations/{station}/transit-arrive")
    public TransitArriveResponse transitArrive(@PathVariable String station,
                                               @Valid @RequestBody TransitArriveRequest request) {
        return baggageService.transitArrive(station, request);
    }

    /** 滞留超时判定：超过上限的行李转 DWELL_EXCEEDED 并阻断后续装载与改派。 */
    @PostMapping("/stations/{station}/dwell-timeout-check")
    public DwellTimeoutCheckResponse checkDwellTimeout(@PathVariable String station,
                                                       @Valid @RequestBody DwellTimeoutCheckRequest request) {
        return baggageService.checkDwellTimeout(station, request);
    }

    /** 复核解除：登记复核结论并指定下一航段，重新校验截止与容量后解除阻断。 */
    @PostMapping("/bags/{bagTag}/release")
    public ReleaseDwellResponse releaseDwell(@PathVariable String bagTag,
                                             @Valid @RequestBody ReleaseDwellRequest request) {
        return baggageService.releaseDwell(bagTag, request);
    }

    /** 改派：整体替换待乘行程，不改写历史超时证据。 */
    @PostMapping("/bags/{bagTag}/reroute")
    public RerouteResponse reroute(@PathVariable String bagTag,
                                   @Valid @RequestBody RerouteRequest request) {
        return baggageService.reroute(bagTag, request);
    }

    /** 行李最新滞留明细查询。 */
    @GetMapping("/bags/{bagTag}/dwell")
    public DwellRecordResponse getDwellDetail(@PathVariable String bagTag) {
        return baggageService.getDwellDetail(bagTag);
    }

    /** 行李滞留历史查询：追加只写事件流，按 seq 稳定升序。 */
    @GetMapping("/bags/{bagTag}/dwell-history")
    public DwellHistoryResponse getDwellHistory(@PathVariable String bagTag) {
        return baggageService.getDwellHistory(bagTag);
    }

    /** 中转点滞留诊断查询。 */
    @GetMapping("/stations/{station}/dwell-diagnostics")
    public StationDwellDiagnostics getStationDwellDiagnostics(@PathVariable String station) {
        return baggageService.getStationDwellDiagnostics(station);
    }
}
