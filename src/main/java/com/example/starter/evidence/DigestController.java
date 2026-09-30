package com.example.starter.evidence;

import com.example.starter.evidence.dto.DigestView;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.ExportPackageView;
import com.example.starter.evidence.dto.RegisterContentRequest;
import com.example.starter.evidence.dto.SealContentRequest;
import com.example.starter.evidence.dto.WitnessDigestRequest;
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

/**
 * 数字摘要见证 API。封存与见证通过 commandKey 幂等；
 * 导出通过请求体内的 requestId 幂等（同键同集合重放首次包，换集合 409）。
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
     * 登记/替换证物当前文件内容：仅当前保管人；不产生封存版本。
     */
    @PostMapping("/{evidenceKey}/contents")
    public ResponseEntity<String> registerContent(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                  @PathVariable String evidenceKey,
                                                  @Valid @RequestBody RegisterContentRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_REGISTER_CONTENT, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.registerContent(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 封存登记：对当前已登记内容计算 SHA-256 并生成新版本；仅当前保管人，重封存产生新版本。
     */
    @PostMapping("/{evidenceKey}/sealed-contents")
    public ResponseEntity<String> sealContent(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String evidenceKey,
                                              @Valid @RequestBody SealContentRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_SEAL_CONTENT, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.sealContent(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 保管人见证指定封存版本；两名不同保管人分别确认同一版本才构成齐备见证。
     */
    @PostMapping("/{evidenceKey}/digest-witnesses")
    public ResponseEntity<String> witnessDigest(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @PathVariable String evidenceKey,
                                                @Valid @RequestBody WitnessDigestRequest request) {
        String hash = idempotencyAdvisor.hash(DigestService.OP_WITNESS_DIGEST, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> digestService.witnessDigest(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 生成导出包：全部证物摘要重算匹配且两名不同见证齐备才成包，否则 422 不生成部分包。
     */
    @PostMapping("/export-packages")
    public ExportPackageView exportPackage(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody ExportPackageRequest request) {
        return digestService.exportPackage(actorId, request);
    }

    /**
     * 查询摘要见证状态（待见证/可导出/摘要异常）、历史见证人与历史导出包清单。
     */
    @GetMapping("/{evidenceKey}/digest")
    public DigestView digest(@PathVariable String evidenceKey) {
        return digestService.digestView(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
