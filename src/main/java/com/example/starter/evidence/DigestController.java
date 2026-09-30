package com.example.starter.evidence;

import com.example.starter.evidence.dto.DigestContentReplaceRequest;
import com.example.starter.evidence.dto.DigestSealRequest;
import com.example.starter.evidence.dto.DigestStatusView;
import com.example.starter.evidence.dto.DigestWitnessRequest;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.ExportPackageView;
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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 数字摘要见证 API：封存登记摘要、两名不同保管人见证、导出前重算校验。
 * 封存/见证沿用 commandKey 幂等；导出以 requestId 幂等：同键同集合重放首次包，同键改集合返回 409。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class DigestController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final DigestService digestService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public DigestController(DigestService digestService, IdempotencyAdvisor idempotencyAdvisor) {
        this.digestService = digestService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 封存：登记当前内容 SHA-256 并产生新版本。
     */
    @PostMapping("/{evidenceKey}/digest/seal")
    public ResponseEntity<String> seal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String evidenceKey,
                                       @Valid @RequestBody DigestSealRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_DIGEST_SEAL, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.seal(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 见证：保管人确认封存版本；两名不同保管人分别确认后可导出。
     */
    @PostMapping("/{evidenceKey}/digest/witnesses")
    public ResponseEntity<String> witness(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String evidenceKey,
                                          @Valid @RequestBody DigestWitnessRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_DIGEST_WITNESS, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.witness(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 替换当前文件内容（不重新封存）：用于模拟文件被改动/恢复；改动后导出 422，恢复后仍按原版本导出。
     */
    @PostMapping("/{evidenceKey}/digest/content")
    public ResponseEntity<String> replaceContent(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String evidenceKey,
                                                 @Valid @RequestBody DigestContentReplaceRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_DIGEST_CONTENT_REPLACE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.replaceContent(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 导出包：摘要全部匹配且见证齐备才生成，否则 422 且无部分包。
     * 请求指纹基于排序去重后的证物集合，故同集合乱序重放仍返回首次包，集合变化返回 409。
     */
    @PostMapping("/exports")
    public ResponseEntity<String> export(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ExportPackageRequest request) {
        List<String> canonicalKeys = new ArrayList<>(new TreeSet<>(request.evidenceKeys()));
        String hash = idempotencyAdvisor.hash(DigestService.OP_DIGEST_EXPORT, actorId,
                "EXPORT:" + request.requestId(), canonicalKeys);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> digestService.export(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询摘要状态：待见证/可导出/摘要异常，含版本、见证人、历史导出清单。
     */
    @GetMapping("/{evidenceKey}/digest/status")
    public DigestStatusView status(@PathVariable String evidenceKey) {
        return digestService.status(evidenceKey);
    }

    /**
     * 按包标识查询导出包（清单已固化，不随后续内容变化改写）。
     */
    @GetMapping("/exports/{packageId}")
    public ExportPackageView getPackage(@PathVariable String packageId) {
        return digestService.getPackage(packageId);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
