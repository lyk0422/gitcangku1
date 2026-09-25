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
import com.example.starter.baggage.BaggageDtos.ClearOverweightRequest;
import com.example.starter.baggage.BaggageDtos.ClearOverweightResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.ReweighHistoryResponse;
import com.example.starter.baggage.BaggageDtos.ReweighRequest;
import com.example.starter.baggage.BaggageDtos.ReweighResponse;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

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

    /** 行李轨迹查询。 */
    @GetMapping("/bags/{bagTag}/trace")
    public BagResponse getBagTrace(@PathVariable String bagTag) {
        return baggageService.getBagTrace(bagTag);
    }

    /** 封舱清单查询。 */
    @GetMapping("/legs/{legId}/manifest")
    public ManifestResponse getManifest(@PathVariable String legId) {
        return baggageService.getManifest(legId);
    }

    /** 复重纠偏：提交实测重量与称重站，原子纠偏并写不可变复重记录，随后触发超重校验。 */
    @PostMapping("/bags/{bagTag}/reweigh")
    public ReweighResponse reweigh(@PathVariable String bagTag, @Valid @RequestBody ReweighRequest request) {
        return baggageService.reweigh(bagTag, request);
    }

    /** 清除超重提醒：需说明，清除不可逆。 */
    @PostMapping("/bags/{bagTag}/overweight/clear")
    public ClearOverweightResponse clearOverweight(@PathVariable String bagTag,
                                                   @Valid @RequestBody ClearOverweightRequest request) {
        return baggageService.clearOverweight(bagTag, request);
    }

    /** 复重历史与当前提醒状态查询。 */
    @GetMapping("/bags/{bagTag}/reweighs")
    public ReweighHistoryResponse getReweighHistory(@PathVariable String bagTag) {
        return baggageService.getReweighHistory(bagTag);
    }
}
