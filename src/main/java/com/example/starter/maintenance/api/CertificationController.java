package com.example.starter.maintenance.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.CertifyBatchRequest;
import com.example.starter.maintenance.api.dto.CertifyBatchResponse;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 工时读数认证 API：批次认证可跨设备，全部校验通过才在一个事务内生效，任一失败整批回滚。
 */
@RestController
@RequestMapping("/api/certifications")
public class CertificationController {

    private final EquipmentService service;

    public CertificationController(EquipmentService service) {
        this.service = service;
    }

    /**
     * 批次认证读数：认证人须不同于各读数当前版本录入人（否则 403）；设备须未退役、
     * 读数版本一致且认证后已认证序列单调不减（否则 422 整批回滚）。
     * certKey 为幂等键：同键同参重放完整重算结果，失败不占键。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CertifyBatchResponse certifyBatch(@Valid @RequestBody CertifyBatchRequest req) {
        return service.certifyBatch(req);
    }
}
