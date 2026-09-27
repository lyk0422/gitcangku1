package com.example.starter.maintenance.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.MaterialBatchResponse;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.service.EvidenceService;

/**
 * 材料批次 API：批次登记与撤销；撤销为终态，已撤销批次不可再被证据包引用。
 */
@RestController
@RequestMapping("/api/material-batches")
public class MaterialController {

    private final EvidenceService service;

    public MaterialController(EvidenceService service) {
        this.service = service;
    }

    /** 登记材料批次：批次编码格式 ^[A-Za-z0-9][A-Za-z0-9-]{2,63}$，全局唯一。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialBatchResponse register(@Valid @RequestBody RegisterMaterialRequest req) {
        return service.registerMaterial(req);
    }

    /** 撤销材料批次：撤销后引用该批次的证据提交与完工确认均被拒绝。 */
    @PostMapping("/{batchCode}/revocations")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialBatchResponse revoke(@PathVariable String batchCode,
                                        @Valid @RequestBody RevokeMaterialRequest req) {
        return service.revokeMaterial(batchCode, req);
    }

    /** 材料批次状态查询。 */
    @GetMapping("/{batchCode}")
    public MaterialBatchResponse get(@PathVariable String batchCode) {
        return service.getMaterial(batchCode);
    }
}
