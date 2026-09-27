package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ItemChainDiagnosisView;
import com.example.starter.evidence.dto.PackageAdjustRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageDetailView;
import com.example.starter.evidence.dto.PackageRevocationView;
import com.example.starter.evidence.dto.PackageRevokeRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证物披露包 API（骨架）。
 */
@RestController
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
     * 创建披露包：规范化证物集合，按截至 UTC 时刻重放历史后冻结。
     */
    @PostMapping("/api/disclosure-packages")
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody PackageCreateRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_CREATE, actorId,
                request.caseKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.createPackage(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 批量增删证物生成更高修订包：完整最终集合整体校验，任一失败不改变已有包版本。
     */
    @PostMapping("/api/disclosure-packages/{packageKey}/adjust")
    public ResponseEntity<String> adjust(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String packageKey,
                                         @Valid @RequestBody PackageAdjustRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_ADJUST, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.adjustPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 交付披露包：仅未交付（FROZEN）包可交付。
     */
    @PostMapping("/api/disclosure-packages/{packageKey}/deliver")
    public ResponseEntity<String> deliver(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String packageKey,
                                          @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_DELIVER, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.deliverPackage(actorId, packageKey, request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 撤销披露包：仅未交付（FROZEN）包可撤销并写原因；已交付包不可撤销。
     */
    @PostMapping("/api/disclosure-packages/{packageKey}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String packageKey,
                                         @Valid @RequestBody PackageRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_REVOKE, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.revokePackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    @GetMapping("/api/disclosure-packages/{packageKey}")
    public PackageDetailView get(@PathVariable String packageKey) {
        return packageService.getPackage(packageKey);
    }

    @GetMapping("/api/disclosure-packages/{packageKey}/diagnosis")
    public List<ItemChainDiagnosisView> diagnose(
            @PathVariable String packageKey) {
        return packageService.diagnose(packageKey);
    }

    @GetMapping("/api/disclosure-packages/{packageKey}/revocation")
    public PackageRevocationView revocation(@PathVariable String packageKey) {
        return packageService.getRevocation(packageKey);
    }

    @GetMapping("/api/cases/{caseKey}/disclosure-packages")
    public List<PackageView> listByCase(@PathVariable String caseKey) {
        return packageService.listByCase(caseKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
