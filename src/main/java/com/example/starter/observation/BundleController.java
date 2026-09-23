package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关联观测簇与字段级联合裁决 API：建簇、联合裁决、簇证据与裁决证据只读查询。
 */
@RestController
@RequestMapping("/api/bundles")
@Validated
public class BundleController {

    private final BundleService bundleService;

    public BundleController(BundleService bundleService) {
        this.bundleService = bundleService;
    }

    /**
     * 建立关联观测簇：冻结各观测 currentVersion 并登记字段冲突/墓碑待恢复项。
     */
    @PostMapping
    public ResponseEntity<BundleResponse> createBundle(@Valid @RequestBody CreateBundleRequest request) {
        BundleService.BundleOutcome outcome = bundleService.createBundle(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 联合裁决：提交簇内全部未决字段冲突的逐字段决定与每条观测 expectedVersion，原子生效。
     */
    @PostMapping("/{bundleKey}/arbitrate")
    public ResponseEntity<ArbitrationResponse> arbitrate(@PathVariable String bundleKey,
                                                         @Valid @RequestBody ArbitrateBundleRequest request) {
        BundleService.ArbitrationOutcome outcome = bundleService.arbitrate(bundleKey, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询簇证据：簇信息、冻结成员与全部字段冲突，按观测、字段稳定排序（只读）。
     */
    @GetMapping("/{bundleKey}")
    public BundleResponse getBundle(@PathVariable String bundleKey) {
        return bundleService.getBundle(bundleKey);
    }

    /**
     * 按联合裁决 requestId 查询不可变裁决记录与逐字段证据（只读）。
     */
    @GetMapping("/arbitrations/{requestId}")
    public ArbitrationResponse getArbitration(@PathVariable String requestId) {
        return bundleService.getArbitration(requestId);
    }
}
