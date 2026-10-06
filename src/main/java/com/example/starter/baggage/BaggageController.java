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
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RetagRequest;
import com.example.starter.baggage.BaggageDtos.RetagResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;

/**
 * 联程行李装载交接 REST 入口。所有写入口都执行“当前标签号 + 代次”门禁。
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

    /** 登记行李及其有序行程（稳定 bagId、初代标签代次 1）。 */
    @PostMapping("/bags")
    public ResponseEntity<BagResponse> registerBag(@Valid @RequestBody RegisterBagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(baggageService.registerBag(request));
    }

    /** 换标：旧标签永久保留为别名，当前代次加一。 */
    @PostMapping("/bags/retag")
    public RetagResponse retag(@Valid @RequestBody RetagRequest request) {
        return baggageService.retag(request);
    }

    /** 批量装载：整批原子，必须出示各行李当前标签号与期望代次。 */
    @PostMapping("/legs/{legId}/load")
    public LoadResponse load(@PathVariable String legId, @Valid @RequestBody LoadRequest request) {
        return baggageService.load(legId, request);
    }

    /** 封舱：校验版本，冻结当时稳定ID/标签/代次的只读清单并转 SEALED。 */
    @PostMapping("/legs/{legId}/seal")
    public SealResponse seal(@PathVariable String legId, @Valid @RequestBody SealRequest request) {
        return baggageService.seal(legId, request);
    }

    /** 到达确认：实际袋号集合须与封舱冻结清单完全一致。 */
    @PostMapping("/legs/{legId}/arrive")
    public ArriveResponse arrive(@PathVariable String legId, @Valid @RequestBody ArriveRequest request) {
        return baggageService.arrive(legId, request);
    }

    /** 差异到达：实际袋号集合须为冻结清单子集，缺失行李转短卸。 */
    @PostMapping("/legs/{legId}/arrive-difference")
    public DifferenceArriveResponse arriveDifference(@PathVariable String legId,
                                                     @Valid @RequestBody DifferenceArriveRequest request) {
        return baggageService.arriveDifference(legId, request);
    }

    /** 补到：短卸行李在缺失航段的应到站实际到达后恢复行程，须匹配当前代次。 */
    @PostMapping("/bags/recover")
    public RecoverResponse recover(@Valid @RequestBody RecoverRequest request) {
        return baggageService.recover(request);
    }

    /** 行李轨迹查询：接受当前标签或任一历史旧标签。 */
    @GetMapping("/bags/{bagTag}/trace")
    public BagResponse getBagTrace(@PathVariable String bagTag) {
        return baggageService.getBagTrace(bagTag);
    }

    /** 封舱清单查询。 */
    @GetMapping("/legs/{legId}/manifest")
    public ManifestResponse getManifest(@PathVariable String legId) {
        return baggageService.getManifest(legId);
    }

    /** 航段差异快照查询：返回只读冻结清单与差异到达实际集合。 */
    @GetMapping("/legs/{legId}/difference")
    public LegDifferenceResponse getDifference(@PathVariable String legId) {
        return baggageService.getDifference(legId);
    }

    /** 未补到短卸行李清单查询。 */
    @GetMapping("/bags/short-unloaded")
    public ShortListResponse listShortUnloaded() {
        return baggageService.listShortUnloaded();
    }
}
