package com.example.starter.pallet;

import com.example.starter.batch.StoredResponse;
import com.example.starter.pallet.dto.ConfigureDividerRequest;
import com.example.starter.pallet.dto.CreatePalletRequest;
import com.example.starter.pallet.dto.DiagnosticsResponse;
import com.example.starter.pallet.dto.LoadBatchesRequest;
import com.example.starter.pallet.dto.PalletDetailResponse;
import com.example.starter.pallet.dto.PalletHistoryResponse;
import com.example.starter.pallet.dto.QuarantineDisposalRequest;
import com.example.starter.pallet.dto.ShipmentReleaseRequest;
import com.example.starter.pallet.dto.UnloadRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次托盘隔离接口：托盘创建、隔板配置、装载、解除装载、装运放行、隔离处置及查询。
 */
@RestController
@RequestMapping("/api/pallets")
public class PalletController {

    private final PalletService service;

    public PalletController(PalletService service) {
        this.service = service;
    }

    /**
     * 创建托盘，初始 OPEN，隔板版本 1。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreatePalletRequest request) {
        return stored(service.createPallet(request));
    }

    /**
     * 变更隔板配置：版本 +1；存在在托批次时拒绝；已装运快照不追溯。
     */
    @PostMapping("/{palletKey}/divider")
    public ResponseEntity<String> configureDivider(@PathVariable String palletKey,
                                                   @Valid @RequestBody ConfigureDividerRequest request) {
        return stored(service.configureDivider(palletKey, request));
    }

    /**
     * 装载批次：一次校验全部批次状态与完整最终隔离关系，全部通过才写入。
     */
    @PostMapping("/{palletKey}/loads")
    public ResponseEntity<String> load(@PathVariable String palletKey,
                                       @Valid @RequestBody LoadBatchesRequest request) {
        return stored(service.load(palletKey, request));
    }

    /**
     * 解除装载：仅托盘未装运时允许；释放分隔位，保留历史。
     */
    @PostMapping("/{palletKey}/unload")
    public ResponseEntity<String> unload(@PathVariable String palletKey,
                                         @Valid @RequestBody UnloadRequest request) {
        return stored(service.unload(palletKey, request));
    }

    /**
     * 装运放行：固化托盘、批次、类别、分隔位与隔板版本快照；X-Actor-Id 为操作人。
     */
    @PostMapping("/{palletKey}/shipment-release")
    public ResponseEntity<String> releaseShipment(
            @PathVariable String palletKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody ShipmentReleaseRequest request) {
        return stored(service.releaseShipment(palletKey, actorId, request));
    }

    /**
     * 隔离处置：已装运托盘发现类别错误时进入隔离处置；装载记录保留不删除。
     */
    @PostMapping("/{palletKey}/quarantine-disposal")
    public ResponseEntity<String> quarantineDisposal(
            @PathVariable String palletKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody QuarantineDisposalRequest request) {
        return stored(service.quarantineDisposal(palletKey, actorId, request));
    }

    /**
     * 托盘明细：当前状态与全部在托装载；只读。
     */
    @GetMapping("/{palletKey}")
    public PalletDetailResponse detail(@PathVariable String palletKey) {
        return service.detail(palletKey);
    }

    /**
     * 托盘历史：全部装载记录、装运快照与隔板变更历史；只读。
     */
    @GetMapping("/{palletKey}/history")
    public PalletHistoryResponse history(@PathVariable String palletKey) {
        return service.history(palletKey);
    }

    /**
     * 托盘隔离诊断：当前在托批次的隔离关系评估（要求值/实际值/差额）；只读。
     */
    @GetMapping("/{palletKey}/diagnostics")
    public DiagnosticsResponse diagnostics(@PathVariable String palletKey) {
        return service.diagnostics(palletKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
