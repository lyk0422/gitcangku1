package com.example.starter.batch;

import com.example.starter.batch.dto.CreatePalletRequest;
import com.example.starter.batch.dto.DisposalRequest;
import com.example.starter.batch.dto.DividerUpdateRequest;
import com.example.starter.batch.dto.LoadRequest;
import com.example.starter.batch.dto.PalletDetailResponse;
import com.example.starter.batch.dto.PalletDiagnosticsResponse;
import com.example.starter.batch.dto.PalletHistoryResponse;
import com.example.starter.batch.dto.ShipRequest;
import com.example.starter.batch.dto.UnloadRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次托盘隔离接口：托盘创建、隔板修改、装载、解除、装运放行、隔离处置及只读查询。
 */
@RestController
@RequestMapping("/api/pallets")
public class PalletController {

    private final PalletService service;

    public PalletController(PalletService service) {
        this.service = service;
    }

    /**
     * 创建托盘，初始状态 OPEN。
     */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreatePalletRequest request) {
        return stored(service.createPallet(request));
    }

    /**
     * 修改隔板数量（版本 +1）；已装运托盘的修改不追溯影响装运快照。
     */
    @PostMapping("/{palletKey}/dividers")
    public ResponseEntity<String> updateDividers(@PathVariable String palletKey,
                                                 @Valid @RequestBody DividerUpdateRequest request) {
        return stored(service.updateDividers(palletKey, request));
    }

    /**
     * 装载批次：一次校验全部批次与最终隔离关系，任一失败整批不写入。
     */
    @PostMapping("/{palletKey}/loads")
    public ResponseEntity<String> load(@PathVariable String palletKey,
                                       @Valid @RequestBody LoadRequest request) {
        return stored(service.load(palletKey, request));
    }

    /**
     * 解除装载：仅托盘未装运时允许，释放分隔位但保留历史。
     */
    @PostMapping("/{palletKey}/unloads")
    public ResponseEntity<String> unload(@PathVariable String palletKey,
                                         @Valid @RequestBody UnloadRequest request) {
        return stored(service.unload(palletKey, request));
    }

    /**
     * 装运放行：核对整批最终状态后一次事务提交，固化快照。
     */
    @PostMapping("/{palletKey}/shipment")
    public ResponseEntity<String> ship(@PathVariable String palletKey,
                                       @Valid @RequestBody ShipRequest request) {
        return stored(service.ship(palletKey, request));
    }

    /**
     * 隔离处置：已装运托盘发现类别错误时进入，装载记录保留。
     */
    @PostMapping("/{palletKey}/disposal")
    public ResponseEntity<String> dispose(@PathVariable String palletKey,
                                          @Valid @RequestBody DisposalRequest request) {
        return stored(service.dispose(palletKey, request));
    }

    /**
     * 托盘明细：概要、当前在托装载、装运快照与处置信息。
     */
    @GetMapping("/{palletKey}")
    public PalletDetailResponse detail(@PathVariable String palletKey) {
        return service.detail(palletKey);
    }

    /**
     * 托盘历史：全部装载事件按发生顺序；from/to 按装载时间左闭右开 [from, to) 过滤。
     */
    @GetMapping("/{palletKey}/history")
    public PalletHistoryResponse history(@PathVariable String palletKey,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        return service.history(palletKey, from, to);
    }

    /**
     * 托盘诊断：隔板实际/要求/差额、类别分隔位映射、装载计数与待复核数。
     */
    @GetMapping("/{palletKey}/diagnostics")
    public PalletDiagnosticsResponse diagnostics(@PathVariable String palletKey) {
        return service.diagnostics(palletKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
