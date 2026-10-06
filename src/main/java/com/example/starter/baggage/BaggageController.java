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
import com.example.starter.baggage.BaggageDtos.DequeueRequest;
import com.example.starter.baggage.BaggageDtos.DequeueResponse;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveResponse;
import com.example.starter.baggage.BaggageDtos.EnqueueRequest;
import com.example.starter.baggage.BaggageDtos.EnqueueResponse;
import com.example.starter.baggage.BaggageDtos.LegDifferenceResponse;
import com.example.starter.baggage.BaggageDtos.LegResponse;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.ManifestResponse;
import com.example.starter.baggage.BaggageDtos.QueueViewResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.ShortListResponse;
import com.example.starter.baggage.BaggageDtos.WithdrawRequest;
import com.example.starter.baggage.BaggageDtos.WithdrawResponse;

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

    /** 入队：绑定 1~5 件当前可装行李，按给定顺序分配递增序号。 */
    @PostMapping("/legs/{legId}/queue/enqueue")
    public EnqueueResponse enqueue(@PathVariable String legId,
                                   @Valid @RequestBody EnqueueRequest request) {
        return baggageService.enqueue(legId, request);
    }

    /** 出队：取队列最前面 count 件有效成员整批装载，冻结出队序号与装载响应。 */
    @PostMapping("/legs/{legId}/queue/dequeue")
    public DequeueResponse dequeue(@PathVariable String legId,
                                   @Valid @RequestBody DequeueRequest request) {
        return baggageService.dequeue(legId, request);
    }

    /** 撤队：撤销一件有效待出队成员，序号保留不重用；已出队不可撤。 */
    @PostMapping("/legs/{legId}/queue/withdraw")
    public WithdrawResponse withdraw(@PathVariable String legId,
                                     @Valid @RequestBody WithdrawRequest request) {
        return baggageService.withdraw(legId, request);
    }

    /** 队列视图：含全部历史成员，按序号升序。 */
    @GetMapping("/legs/{legId}/queue")
    public QueueViewResponse getQueue(@PathVariable String legId) {
        return baggageService.getQueue(legId);
    }
}
