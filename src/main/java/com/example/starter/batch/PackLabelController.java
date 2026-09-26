package com.example.starter.batch;

import com.example.starter.batch.dto.CartonResponse;
import com.example.starter.batch.dto.ReconciliationResponse;
import com.example.starter.batch.dto.RegisterPackPlanRequest;
import com.example.starter.batch.dto.SealCartonsRequest;
import com.example.starter.batch.dto.VoidCartonRequest;
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
 * 批次包装标签核销接口：计划登记、批量封箱、封箱作废、明细与诊断查询。
 */
@RestController
@RequestMapping("/api/batches")
public class PackLabelController {

    private final PackLabelService service;

    public PackLabelController(PackLabelService service) {
        this.service = service;
    }

    /**
     * 登记包装计划：计划数量与连续标签号段（左闭右开），每批次一份。
     */
    @PostMapping("/{batchKey}/pack-plan")
    public ResponseEntity<String> registerPlan(@PathVariable String batchKey,
                                               @Valid @RequestBody RegisterPackPlanRequest request) {
        return stored(service.registerPlan(batchKey, request));
    }

    /**
     * 批量封箱：任一标签校验失败整次回滚，不留下部分占用。
     */
    @PostMapping("/{batchKey}/cartons")
    public ResponseEntity<String> sealCartons(@PathVariable String batchKey,
                                              @Valid @RequestBody SealCartonsRequest request) {
        return stored(service.sealCartons(batchKey, request));
    }

    /**
     * 作废封箱：仅未放行批次允许；写入原因并释放数量与标签占用。
     */
    @PostMapping("/{batchKey}/cartons/{cartonKey}/void")
    public ResponseEntity<String> voidCarton(@PathVariable String batchKey,
                                             @PathVariable String cartonKey,
                                             @Valid @RequestBody VoidCartonRequest request) {
        return stored(service.voidCarton(batchKey, cartonKey, request));
    }

    /**
     * 封箱明细与历史：含已作废封箱，按提交顺序排列；读取不改变状态。
     */
    @GetMapping("/{batchKey}/cartons")
    public List<CartonResponse> listCartons(@PathVariable String batchKey) {
        return service.listCartons(batchKey);
    }

    /**
     * 标签核销诊断：计划/实际/差额、标签占用计数、放行快照与差异诊断；读取不改变状态。
     */
    @GetMapping("/{batchKey}/pack-reconciliation")
    public ReconciliationResponse reconciliation(@PathVariable String batchKey) {
        return service.reconciliation(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
