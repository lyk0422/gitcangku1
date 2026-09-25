package com.example.starter.water;

import com.example.starter.water.dto.Dtos.BatchSettleRequest;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.ChannelChangeRequest;
import com.example.starter.water.dto.Dtos.ChannelResponse;
import com.example.starter.water.dto.Dtos.CreateOutageRequest;
import com.example.starter.water.dto.Dtos.OutageCommandRequest;
import com.example.starter.water.dto.Dtos.OutageImpactResponse;
import com.example.starter.water.dto.Dtos.OutageListResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.RecoverOutageRequest;
import com.example.starter.water.dto.Dtos.RiskListResponse;
import com.example.starter.water.dto.Dtos.SettleRequest;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementRejectionResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 输水渠停运窗口与配额核销 REST 接口。
 * 所有写命令携带幂等键；渠道变更类命令携带 expectedVersion 乐观并发校验；
 * 核销类写操作通过 X-Actor-Id 识别操作人（须为申请人本人）。
 */
@RestController
@RequestMapping("/api")
public class CanalController {

    private final CanalService service;

    public CanalController(CanalService service) {
        this.service = service;
    }

    /** 渠道变更：设置核销容量上限（null 表示不限）。 */
    @PostMapping("/channels/{channelId}/change")
    public ChannelResponse changeChannel(@PathVariable String channelId,
                                         @RequestBody ChannelChangeRequest request) {
        return service.changeChannel(request.commandKey(), channelId, request.capacity(),
                request.expectedVersion());
    }

    /** 查询渠道。 */
    @GetMapping("/channels/{channelId}")
    public ChannelResponse getChannel(@PathVariable String channelId) {
        return service.getChannel(channelId);
    }

    /** 下达停运窗口。 */
    @PostMapping("/channels/{channelId}/outages")
    public OutageResponse createOutage(@PathVariable String channelId,
                                       @RequestBody CreateOutageRequest request) {
        return service.createOutage(request.commandKey(), request.outageKey(), channelId,
                request.startUtc(), request.endUtc(), request.affectedAllocationKeys(),
                request.expectedVersion());
    }

    /** 查询渠道全部停运窗口。 */
    @GetMapping("/channels/{channelId}/outages")
    public OutageListResponse listOutages(@PathVariable String channelId) {
        return service.listOutages(channelId);
    }

    /** 删除停运窗口（仅未开始可删）。 */
    @PostMapping("/outages/{outageKey}/delete")
    public OutageResponse deleteOutage(@PathVariable String outageKey,
                                       @RequestBody OutageCommandRequest request) {
        return service.deleteOutage(request.commandKey(), outageKey, request.expectedVersion());
    }

    /** 记录停运窗口提前恢复时刻（窗口关闭）。 */
    @PostMapping("/outages/{outageKey}/recover")
    public OutageResponse recoverOutage(@PathVariable String outageKey,
                                        @RequestBody RecoverOutageRequest request) {
        return service.recoverOutage(request.commandKey(), outageKey, request.expectedVersion(),
                request.recoveredAtUtc());
    }

    /** 查询停运影响：受影响申请及其供水窗口与生效停运区间的相交情况。 */
    @GetMapping("/outages/{outageKey}/impact")
    public OutageImpactResponse getOutageImpact(@PathVariable String outageKey) {
        return service.getOutageImpact(outageKey);
    }

    /** 查询申请的供应风险。 */
    @GetMapping("/allocations/{allocationKey}/risks")
    public RiskListResponse getAllocationRisks(@PathVariable String allocationKey) {
        return service.getAllocationRisks(allocationKey);
    }

    /** 单笔核销。 */
    @PostMapping("/allocations/{allocationKey}/settle")
    public SettlementResponse settle(@PathVariable String allocationKey,
                                     @RequestBody SettleRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.settle(request.commandKey(), request.settlementKey(), allocationKey,
                request.amount(), actor);
    }

    /** 批量核销：任一申请预校验失败则全部回滚。 */
    @PostMapping("/settlements/batch")
    public BatchSettleResponse settleBatch(@RequestBody BatchSettleRequest request,
                                           @RequestHeader("X-Actor-Id") String actor) {
        return service.settleBatch(request.commandKey(), request.items(), actor);
    }

    /** 查询申请的核销流水。 */
    @GetMapping("/allocations/{allocationKey}/settlements")
    public SettlementListResponse getSettlements(@PathVariable String allocationKey) {
        return service.getSettlements(allocationKey);
    }

    /** 查询核销拒绝原因（只读预览）。 */
    @GetMapping("/allocations/{allocationKey}/settlement-rejection")
    public SettlementRejectionResponse previewSettlement(@PathVariable String allocationKey,
                                                         @RequestParam String amount) {
        return service.previewSettlement(allocationKey, amount);
    }
}
