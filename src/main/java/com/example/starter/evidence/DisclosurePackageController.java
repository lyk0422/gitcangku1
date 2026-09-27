package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemDiagnosisView;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
import com.example.starter.evidence.dto.PackageRevocationView;
import com.example.starter.evidence.dto.PackageRevokeRequest;
import com.example.starter.evidence.dto.PackageSummaryView;
import com.example.starter.evidence.dto.PackageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证物披露包 API。建包以 packageKey 为幂等键（指纹含案件、规范化证物、截至时刻、
 * 包版本与操作者）：同键同指纹重放返回首次结果，同键改参返回 409，失败不占键。
 * 增删/交付/撤销使用 commandKey 幂等；所有写操作按事务提交顺序裁决。
 */
@RestController
@RequestMapping("/api/disclosure-packages")
@Validated
public class DisclosurePackageController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final DisclosurePackageService packageService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public DisclosurePackageController(DisclosurePackageService packageService,
                                       IdempotencyAdvisor idempotencyAdvisor) {
        this.packageService = packageService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建披露包：按截至时刻重放每件证物历史状态，全部通过后整包冻结（201）。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody PackageCreateRequest request) {
        String fingerprint = packageService.fingerprintOf(actorId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.packageKey(), fingerprint,
                () -> packageService.createPackage(actorId, request, fingerprint));
        return toEntity(response);
    }

    /**
     * 批量增删证物：先校验完整最终集合，任一失败整批不生效；仅 FROZEN 包可增删。
     */
    @PostMapping("/{packageKey}/items")
    public ResponseEntity<String> updateItems(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String packageKey,
                                              @Valid @RequestBody PackageItemsUpdateRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_ITEMS_UPDATE,
                actorId, packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.updateItems(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 交付披露包：仅 FROZEN 包可交付；交付后不可撤销、不可增删。
     */
    @PostMapping("/{packageKey}/deliver")
    public ResponseEntity<String> deliver(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String packageKey,
                                          @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_DELIVER,
                actorId, packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.deliver(actorId, packageKey, request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 撤销披露包：仅未交付包可撤销，必须写原因；已交付包须创建更高修订包。
     */
    @PostMapping("/{packageKey}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String packageKey,
                                         @Valid @RequestBody PackageRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_REVOKE,
                actorId, packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.revoke(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询包内容：包头 + 按规范排序的冻结明细。
     */
    @GetMapping("/{packageKey}")
    public PackageView getPackage(@PathVariable String packageKey) {
        return packageService.getPackage(packageKey);
    }

    /**
     * 查询冻结摘要：包指纹、整包摘要与逐件链版本/链摘要。
     */
    @GetMapping("/{packageKey}/summary")
    public PackageSummaryView summary(@PathVariable String packageKey) {
        return packageService.summary(packageKey);
    }

    /**
     * 逐件链诊断：冻结快照与当前实时链状态对照。
     */
    @GetMapping("/{packageKey}/items/{evidenceKey}/diagnosis")
    public PackageItemDiagnosisView itemDiagnosis(@PathVariable String packageKey,
                                                  @PathVariable String evidenceKey) {
        return packageService.itemDiagnosis(packageKey, evidenceKey);
    }

    /**
     * 查询撤销记录。
     */
    @GetMapping("/{packageKey}/revocation")
    public PackageRevocationView revocation(@PathVariable String packageKey) {
        return packageService.revocation(packageKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
