package com.example.starter.web;

import com.example.starter.idempotency.IdempotentOutcome;
import com.example.starter.service.PackageService;
import com.example.starter.web.dto.Requests.AddItemRequest;
import com.example.starter.web.dto.Requests.CreatePackageRequest;
import com.example.starter.web.dto.Requests.DamageRequest;
import com.example.starter.web.dto.Requests.HandoverRequest;
import com.example.starter.web.dto.Responses.PackageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 封存包入口：建包、装包、交接、封签异常、重新封存与查询。
 */
@RestController
@RequestMapping("/api/packages")
public class PackageController {

    private final PackageService packageService;

    public PackageController(PackageService packageService) {
        this.packageService = packageService;
    }

    /**
     * 在指定层位建包，返回 201。
     */
    @PostMapping
    public ResponseEntity<PackageResponse> create(@Valid @RequestBody CreatePackageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(packageService.create(req));
    }

    /**
     * 查询封存包：展示版本、样本清单与每次封签状态。
     */
    @GetMapping("/{packageNo}")
    public PackageResponse get(@PathVariable String packageNo) {
        return packageService.getByPackageNo(packageNo);
    }

    /**
     * 样本入包：跨层位 422，超过 20 件 422，未采集 422，样本已被占用 409。
     */
    @PostMapping("/{packageNo}/items")
    public PackageResponse addItem(@PathVariable String packageNo,
            @Valid @RequestBody AddItemRequest req) {
        return packageService.addItem(packageNo, req);
    }

    /**
     * 交接（幂等）：锁定样本清单与封签编号，整包原子生效。
     */
    @PostMapping("/{packageNo}/handover")
    public ResponseEntity<String> handover(@PathVariable String packageNo,
            @Valid @RequestBody HandoverRequest req) {
        IdempotentOutcome outcome = packageService.handover(packageNo, req);
        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON).body(outcome.body());
    }

    /**
     * 封签破损上报：只追加异常记录并停止后续交接。
     */
    @PostMapping("/{packageNo}/damage")
    public PackageResponse reportDamage(@PathVariable String packageNo,
            @Valid @RequestBody DamageRequest req) {
        return packageService.reportDamage(packageNo, req);
    }

    /**
     * 重新封存：生成新包并引用旧包异常记录。
     */
    @PostMapping("/{packageNo}/reseal")
    public ResponseEntity<PackageResponse> reseal(@PathVariable String packageNo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(packageService.reseal(packageNo));
    }
}