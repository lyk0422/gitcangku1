package com.example.starter.maintenance.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.CertifyReadingsRequest;
import com.example.starter.maintenance.api.dto.CertifyReadingsResponse;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 读数认证 API：一个批次可跨设备认证多条读数，先按设备与读数时刻排序验证最终
 * 已认证序列单调不减，任一校验失败整批回滚；certKey 幂等，同键成功重放完整重算结果。
 */
@RestController
@RequestMapping("/api/certifications")
public class CertificationController {

    private final EquipmentService service;

    public CertificationController(EquipmentService service) {
        this.service = service;
    }

    /** 批量认证读数：认证人须不同于每条读数的录入人（否则 403）；读数版本不符、设备已退役或序列倒退返回 422。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CertifyReadingsResponse certify(@Valid @RequestBody CertifyReadingsRequest req) {
        return service.certify(req);
    }
}
