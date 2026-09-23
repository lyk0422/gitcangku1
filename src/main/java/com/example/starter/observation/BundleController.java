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

import java.util.List;

/**
 * 关联观测簇 API：建簇、簇内冲突登记、字段级联合裁决与只读证据查询。
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
     * 建立关联观测簇：冻结各成员当前版本，墓碑成员以待恢复身份入簇。
     */
    @PostMapping
    public ResponseEntity<BundleResponse> createBundle(@Valid @RequestBody CreateBundleRequest request) {
        BundleService.BundleOutcome outcome = bundleService.createBundle(request);
        return ResponseEntity.status(outcome.status())
                .body((BundleResponse) outcome.body());
    }

    /**
     * 登记簇内成员的离线冲突候选；服务端重算冲突字段并整组保存候选快照。
     */
    @PostMapping("/{bundleKey}/conflicts")
    public ResponseEntity<BundleResponse> registerConflict(@PathVariable String bundleKey,
                                                           @Valid @RequestBody
                                                           RegisterBundleConflictRequest request) {
        BundleService.BundleOutcome outcome = bundleService.registerConflict(bundleKey, request);
        return ResponseEntity.status(outcome.status())
                .body((BundleResponse) outcome.body());
    }

    /**
     * 字段级联合裁决：原子完成冲突解决、墓碑恢复、版本生成与簇关闭。
     */
    @PostMapping("/{bundleKey}/arbitrations")
    public ResponseEntity<ArbitrationResponse> arbitrate(@PathVariable String bundleKey,
                                                         @Valid @RequestBody
                                                         ArbitrateBundleRequest request) {
        BundleService.ArbitrateOutcome outcome = bundleService.arbitrate(bundleKey, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 证据查询：簇信息、成员与未解决冲突，按观测、字段稳定排序（只读）。
     */
    @GetMapping("/{bundleKey}")
    public BundleResponse getBundle(@PathVariable String bundleKey) {
        return bundleService.getBundle(bundleKey);
    }

    /**
     * 按簇查询全部联合裁决记录，按裁决时刻先后排序（只读）。
     */
    @GetMapping("/{bundleKey}/arbitrations")
    public List<ArbitrationResponse> listArbitrations(@PathVariable String bundleKey) {
        return bundleService.listArbitrations(bundleKey);
    }

    /**
     * 按全局裁决标识查询不可变联合裁决记录（只读）。
     */
    @GetMapping("/arbitrations/{arbitrationId}")
    public ArbitrationResponse getArbitration(@PathVariable String arbitrationId) {
        return bundleService.getArbitration(arbitrationId);
    }
}
