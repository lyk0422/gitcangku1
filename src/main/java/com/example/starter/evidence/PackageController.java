package com.example.starter.evidence;

import com.example.starter.evidence.dto.PackageCancelRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageReturnRequest;
import com.example.starter.evidence.dto.PackageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.Comparator;
import java.util.Map;

/**
 * 组合借出包 API：整包创建、分批归还、撤销与只读查询。
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果（子集换序视为同参），同键改参返回 409，失败不占键。
 */
@RestController
@RequestMapping("/api/evidence/packages")
@Validated
public class PackageController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final PackageService packageService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public PackageController(PackageService packageService, IdempotencyAdvisor idempotencyAdvisor) {
        this.packageService = packageService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建组合借出包：2~20 件同案件、同保管点且当前可借证物整体借出。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody PackageCreateRequest raw) {
        // 子集换序视为同参：幂等指纹与落库前统一按证物键排序。
        PackageCreateRequest request = canonical(raw);
        String hash = idempotencyAdvisor.hash(PackageService.OP_PACKAGE_CREATE, actorId,
                request.packageKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.createPackage(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 分批归还：包内一个非空子集，双人确认，整批事务；最后一件归还时自动关闭。
     */
    @PostMapping("/{packageKey}/returns")
    public ResponseEntity<String> returns(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String packageKey,
                                          @Valid @RequestBody PackageReturnRequest raw) {
        PackageReturnRequest request = canonical(raw);
        String hash = idempotencyAdvisor.hash(PackageService.OP_PACKAGE_RETURN, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.returnPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 借出撤销：尚无任何归还且全部证物仍在借出人名下时原子执行。
     */
    @PostMapping("/{packageKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String packageKey,
                                         @Valid @RequestBody PackageCancelRequest request) {
        String hash = idempotencyAdvisor.hash(PackageService.OP_PACKAGE_CANCEL, actorId,
                packageKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> packageService.cancelPackage(actorId, packageKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询组合包、剩余未归还集合及链路证据（只读，稳定排序）。
     */
    @GetMapping("/{packageKey}")
    public PackageView get(@PathVariable String packageKey) {
        return packageService.getPackage(packageKey);
    }

    /**
     * 案件授权登记：为操作人追加案件权限（组合借出经办人、归还接收人/复核人前置条件）。
     * 授权幂等，重复登记不报错。
     */
    @PostMapping("/cases/{caseKey}/grants/{userId}")
    public ResponseEntity<Map<String, Object>> grantCase(@PathVariable String caseKey,
                                                         @PathVariable String userId) {
        packageService.grantCase(caseKey, userId);
        return ResponseEntity.ok(Map.of("caseKey", caseKey, "userId", userId, "granted", true));
    }

    private PackageCreateRequest canonical(PackageCreateRequest raw) {
        return new PackageCreateRequest(raw.commandKey(), raw.packageKey(), raw.purpose(),
                raw.borrowerId(), raw.dueAt(),
                raw.items().stream()
                        .sorted(Comparator.comparing(PackageCreateRequest.PackageItemRequest::evidenceKey))
                        .toList());
    }

    private PackageReturnRequest canonical(PackageReturnRequest raw) {
        return new PackageReturnRequest(raw.commandKey(), raw.receiverId(), raw.reviewerId(),
                raw.note(),
                raw.items().stream()
                        .sorted(Comparator.comparing(PackageReturnRequest.ReturnItemRequest::evidenceKey))
                        .toList());
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
