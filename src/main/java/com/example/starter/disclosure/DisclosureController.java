package com.example.starter.disclosure;

import com.example.starter.evidence.IdempotencyAdvisor;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.disclosure.dto.PackageAmendRequest;
import com.example.starter.disclosure.dto.PackageContentView;
import com.example.starter.disclosure.dto.PackageCreateRequest;
import com.example.starter.disclosure.dto.PackageRevokeRequest;
import com.example.starter.disclosure.dto.PackageSummaryView;
import com.example.starter.disclosure.dto.PackageView;
import com.example.starter.disclosure.dto.RevocationRecordView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 证物披露包 API。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409，业务失败不占键。
 */
@RestController
@RequestMapping("/api/disclosure")
@Validated
public class DisclosureController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final DisclosureService disclosureService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public DisclosureController(DisclosureService disclosureService,
                                IdempotencyAdvisor idempotencyAdvisor) {
        this.disclosureService = disclosureService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建案件披露包：选择规范化排序的证物集合与截至 UTC 时刻，整包校验通过才生成 v1。
     */
    @PostMapping("/packages")
    public ResponseEntity<String> createPackage(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody PackageCreateRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosureService.OP_PACKAGE_CREATE, actorId,
                request.caseKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> disclosureService.createPackage(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 批量增删证物：对完整最终集合整包校验，任一失败不生成修订版本、不改变已有包。
     */
    @PostMapping("/packages/{packageKey}/amend")
    public ResponseEntity<String> amendPackage(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String packageKey,
            @Valid @RequestBody PackageAmendRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosureService.OP_PACKAGE_AMEND, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> disclosureService.amendPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 交付披露包：仅未交付包可交付，已交付包不可撤销。
     */
    @PostMapping("/packages/{packageKey}/deliver")
    public ResponseEntity<String> deliverPackage(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String packageKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosureService.OP_PACKAGE_DELIVER, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> disclosureService.deliverPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销披露包：仅未交付包可撤销且必须写明原因；已交付包返回 409。
     */
    @PostMapping("/packages/{packageKey}/revoke")
    public ResponseEntity<String> revokePackage(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String packageKey,
            @Valid @RequestBody PackageRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(DisclosureService.OP_PACKAGE_REVOKE, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> disclosureService.revokePackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询包内容：包视图 + 按规范化顺序排列的逐件冻结快照。
     */
    @GetMapping("/packages/{packageKey}")
    public PackageContentView getPackage(@PathVariable String packageKey) {
        return disclosureService.getPackageContent(packageKey);
    }

    /**
     * 查询冻结摘要（建包时固化，生成后不受迁移/复核/新增链记录影响）。
     */
    @GetMapping("/packages/{packageKey}/summary")
    public PackageSummaryView getSummary(@PathVariable String packageKey) {
        return disclosureService.getPackageSummary(packageKey);
    }

    /**
     * 查询撤销记录；未撤销包返回 404。
     */
    @GetMapping("/packages/{packageKey}/revocation")
    public RevocationRecordView getRevocation(@PathVariable String packageKey) {
        return disclosureService.getRevocation(packageKey);
    }

    /**
     * 按版本顺序列出案件全部包（含已交付与已撤销）。
     */
    @GetMapping("/cases/{caseKey}/packages")
    public List<PackageView> listByCase(@PathVariable String caseKey) {
        return disclosureService.listByCase(caseKey);
    }

    /**
     * 逐件链诊断：按包自身截至时刻（或 cutoffAt 覆盖值）重放单件证物，返回可区分失败原因码。
     */
    @GetMapping("/packages/{packageKey}/items/{evidenceKey}/diagnosis")
    public Map<String, Object> diagnoseItem(
            @PathVariable String packageKey,
            @PathVariable String evidenceKey,
            @RequestParam(value = "cutoffAt", required = false) LocalDateTime cutoffAt) {
        return disclosureService.diagnoseItem(packageKey, evidenceKey, cutoffAt);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
