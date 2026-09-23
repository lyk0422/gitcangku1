package com.example.starter.evidence;

import com.example.starter.evidence.dto.CasePermissionGrantRequest;
import com.example.starter.evidence.dto.LoanPackageView;
import com.example.starter.evidence.dto.PackageCancelRequest;
import com.example.starter.evidence.dto.PackageChainView;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageRemainingView;
import com.example.starter.evidence.dto.PackageReturnRequest;
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
 * 组合借出包 API。所有操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 requestId 保证幂等：同键同参重放返回首次结果（子集换序视为同参），
 * 同键改参返回 409，失败不占键。
 */
@RestController
@RequestMapping("/api/evidence/packages")
@Validated
public class LoanPackageController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final LoanPackageService packageService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public LoanPackageController(LoanPackageService packageService,
                                 IdempotencyAdvisor idempotencyAdvisor) {
        this.packageService = packageService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记案件权限：归还的接收人/复核人均须事先登记。
     */
    @PostMapping("/case-permissions")
    public ResponseEntity<String> grantPermission(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                  @Valid @RequestBody CasePermissionGrantRequest request) {
        String hash = idempotencyAdvisor.hash(LoanPackageService.OP_CASE_PERMISSION_GRANT, actorId,
                request.caseKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> packageService.grantPermission(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 创建组合借出包：2~20 件同案件、同保管点可借证物；任一件不满足则整包失败。
     */
    @PostMapping
    public ResponseEntity<String> createPackage(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @Valid @RequestBody PackageCreateRequest request) {
        PackageCreateRequest canonical = request.canonical();
        String hash = idempotencyAdvisor.hash(LoanPackageService.OP_PACKAGE_CREATE, actorId,
                request.packageKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> packageService.createPackage(actorId, canonical, hash));
        return toEntity(response);
    }

    /**
     * 分批归还：非空子集 + 各件冻结 sealVersion + 接收人/复核人（不同且均有案件权限）。
     */
    @PostMapping("/{packageKey}/returns")
    public ResponseEntity<String> returnBatch(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String packageKey,
                                              @Valid @RequestBody PackageReturnRequest request) {
        PackageReturnRequest canonical = request.canonical();
        String hash = idempotencyAdvisor.hash(LoanPackageService.OP_PACKAGE_RETURN, actorId,
                packageKey, canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> packageService.returnBatch(actorId, packageKey, canonical, hash));
        return toEntity(response);
    }

    /**
     * 借出撤销：仅尚无任何归还且全部证物仍在借出人名下时原子执行。
     */
    @PostMapping("/{packageKey}/cancel")
    public ResponseEntity<String> cancelPackage(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @PathVariable String packageKey,
                                                @Valid @RequestBody PackageCancelRequest request) {
        String hash = idempotencyAdvisor.hash(LoanPackageService.OP_PACKAGE_CANCEL, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> packageService.cancelPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询组合包：全部借出明细与归还批次，只读并稳定排序。
     */
    @GetMapping("/{packageKey}")
    public LoanPackageView getPackage(@PathVariable String packageKey) {
        return packageService.getPackage(packageKey);
    }

    /**
     * 查询组合包剩余未归还集合，只读并稳定排序。
     */
    @GetMapping("/{packageKey}/remaining")
    public PackageRemainingView getRemaining(@PathVariable String packageKey) {
        return packageService.getRemaining(packageKey);
    }

    /**
     * 查询组合包链路证据：借出明细 + 全部归还批次 + 关闭快照。
     */
    @GetMapping("/{packageKey}/chain")
    public PackageChainView getChain(@PathVariable String packageKey) {
        return packageService.getChain(packageKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
