package com.example.starter.batch;

import com.example.starter.batch.dto.ConfigureDividersRequest;
import com.example.starter.batch.dto.CreatePalletRequest;
import com.example.starter.batch.dto.DeclareCategoryRequest;
import com.example.starter.batch.dto.LoadPalletRequest;
import com.example.starter.batch.dto.PalletDetailResponse;
import com.example.starter.batch.dto.PalletResponse;
import com.example.starter.batch.dto.QuarantinePalletRequest;
import com.example.starter.batch.dto.ShipPalletRequest;
import com.example.starter.batch.dto.UnloadPalletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批次托盘隔离接口：隔离类别声明、托盘配置、装载/解除、装运放行、隔离处置及只读诊断查询。
 */
@RestController
@RequestMapping("/api")
public class PalletController {

    private final PalletService palletService;

    public PalletController(PalletService palletService) {
        this.palletService = palletService;
    }

    /**
     * 声明批次隔离类别；类别一经声明不可更改。
     */
    @PostMapping("/batches/{batchKey}/segregation-category")
    public ResponseEntity<String> declareCategory(@PathVariable String batchKey,
                                                  @Valid @RequestBody DeclareCategoryRequest request) {
        return stored(palletService.declareCategory(batchKey, request.segregationCategory(),
                request.commandKey()));
    }

    /**
     * 创建托盘，初始状态 OPEN。
     */
    @PostMapping("/pallets")
    public ResponseEntity<String> createPallet(@Valid @RequestBody CreatePalletRequest request) {
        return stored(palletService.createPallet(request.palletKey(), request.dividerCount(),
                request.commandKey()));
    }

    /**
     * 配置托盘有效隔板数量；仅未装运托盘可改，产生新隔板版本且不追溯已装运记录。
     */
    @PostMapping("/pallets/{palletKey}/dividers")
    public ResponseEntity<String> configureDividers(@PathVariable String palletKey,
                                                    @Valid @RequestBody ConfigureDividersRequest request) {
        return stored(palletService.configureDividers(palletKey, request.dividerCount(),
                request.commandKey()));
    }

    /**
     * 整批装载：服务端一次校验最终隔离关系，任一失败 422 整体不写入。
     */
    @PostMapping("/pallets/{palletKey}/loadings")
    public ResponseEntity<String> load(@PathVariable String palletKey,
                                       @Valid @RequestBody LoadPalletRequest request) {
        return stored(palletService.load(palletKey, request));
    }

    /**
     * 解除装载：仅未装运托盘，释放分隔位但保留装载历史。
     */
    @PostMapping("/pallets/{palletKey}/unload")
    public ResponseEntity<String> unload(@PathVariable String palletKey,
                                         @Valid @RequestBody UnloadPalletRequest request) {
        return stored(palletService.unload(palletKey, request));
    }

    /**
     * 装运放行：固化托盘、批次、类别、分隔位与隔板版本快照。
     */
    @PostMapping("/pallets/{palletKey}/shipment")
    public ResponseEntity<String> ship(@PathVariable String palletKey,
                                       @Valid @RequestBody ShipPalletRequest request) {
        return stored(palletService.ship(palletKey, request.commandKey()));
    }

    /**
     * 已装运托盘发现类别错误：进入隔离处置，不删除装载记录。
     */
    @PostMapping("/pallets/{palletKey}/quarantine")
    public ResponseEntity<String> quarantine(@PathVariable String palletKey,
                                             @Valid @RequestBody QuarantinePalletRequest request) {
        return stored(palletService.quarantine(palletKey, request.reason(), request.commandKey()));
    }

    /**
     * 托盘列表（只读概要），按创建顺序排列。
     */
    @GetMapping("/pallets")
    public List<PalletResponse> list() {
        return palletService.listPallets();
    }

    /**
     * 托盘完整诊断视图（只读）：当前装载、装载历史、装运快照、事件历史与召回联动待复核标记。
     */
    @GetMapping("/pallets/{palletKey}")
    public PalletDetailResponse detail(@PathVariable String palletKey) {
        return palletService.palletDetail(palletKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
