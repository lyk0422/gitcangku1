package com.example.starter.evidence;

import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageDeliverRequest;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageItemsUpdateRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证物披露包 API。操作人通过 X-Actor-Id 请求头提供；写操作携带 commandKey 保证幂等：
 * 同键同参重放返回首次结果，同键改参返回 409，失败请求不占用命令键。
 */
@RestController
@RequestMapping("/api/cases/{caseKey}/disclosure-packages")
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
     * 创建案件披露包（版本 1 或首个版本）。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String caseKey,
                                         @Valid @RequestBody PackageCreateRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_CREATE, actorId,
                caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.createPackage(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 批量增删证物：请求体为变更后的完整最终集合，生成更高修订版本。
     */
    @PostMapping("/versions/latest/items")
    public ResponseEntity<String> updateItems(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String caseKey,
                                              @Valid @RequestBody PackageItemsUpdateRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_UPDATE_ITEMS,
                actorId, caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.updateItems(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 交付案件最新版本。
     */
    @PostMapping("/versions/latest/deliver")
    public ResponseEntity<String> deliver(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String caseKey,
                                          @Valid @RequestBody PackageDeliverRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_DELIVER, actorId,
                caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.deliver(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销案件最新版本（仅未交付可撤销，原因非空）。
     */
    @PostMapping("/versions/latest/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String caseKey,
                                         @Valid @RequestBody PackageRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosurePackageService.OP_PACKAGE_REVOKE, actorId,
                caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.revoke(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询案件全部包版本（版本号升序）。
     */
    @GetMapping
    public List<PackageView> list(@PathVariable String caseKey) {
        return packageService.listPackages(caseKey);
    }

    /**
     * 查询案件最新版本包内容（冻结摘要 + 逐件固化内容 + 交付/撤销记录）。
     */
    @GetMapping("/versions/latest")
    public PackageView latest(@PathVariable String caseKey) {
        return packageService.getLatestPackage(caseKey);
    }

    /**
     * 查询指定版本包内容。
     */
    @GetMapping("/versions/{packageVersion}")
    public PackageView get(@PathVariable String caseKey,
                           @PathVariable int packageVersion) {
        return packageService.getPackage(caseKey, packageVersion);
    }

    /**
     * 查询指定版本中单件证物的逐件链诊断与固化快照。
     */
    @GetMapping("/versions/{packageVersion}/items/{evidenceKey}")
    public PackageItemView itemDiagnosis(@PathVariable String caseKey,
                                         @PathVariable int packageVersion,
                                         @PathVariable String evidenceKey) {
        return packageService.getItemDiagnosis(caseKey, packageVersion, evidenceKey);
    }

    /**
     * 查询指定版本的撤销记录。
     */
    @GetMapping("/versions/{packageVersion}/revocation")
    public PackageRevocationView revocation(@PathVariable String caseKey,
                                            @PathVariable int packageVersion) {
        return packageService.getRevocation(caseKey, packageVersion);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
