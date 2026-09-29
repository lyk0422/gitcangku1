package com.example.starter.translation.archive;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布撤回、术语退役与译文归档包 REST API。
 * 写操作复用 requestId 幂等机制；操作者通过 X-Actor-Id 传入并纳入幂等指纹。
 * 所有时间按 UTC 输出；历史区间参数 from/to 按左闭右开解释。
 */
@RestController
@RequestMapping("/api/documents/{documentId}")
public class ArchiveController {

    private final ArchiveService archiveService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public ArchiveController(ArchiveService archiveService, WriteExecutor writeExecutor,
                             ObjectMapper objectMapper) {
        this.archiveService = archiveService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 撤回指定发布版本：终态；撤回后不可再对该版本归档，已有归档仍可受限查询。 */
    @PostMapping("/releases/{publishedVersion}/withdraw")
    public ResponseEntity<String> withdrawRelease(@PathVariable long documentId,
                                                  @PathVariable int publishedVersion,
                                                  @RequestHeader("X-Actor-Id") String actorId,
                                                  @Valid @RequestBody ArchiveDtos.WithdrawReleaseRequest request) {
        String operation = "POST /api/documents/" + documentId + "/releases/" + publishedVersion + "/withdraw";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request.reason()),
                () -> WriteResult.of(200,
                        archiveService.withdrawRelease(documentId, publishedVersion, actorId, request)))
                .toResponseEntity();
    }

    /** 退役指定术语版本：仅标记，不删除规则、不影响归档证据。 */
    @PostMapping("/terms/{termVersion}/retire")
    public ResponseEntity<String> retireTerms(@PathVariable long documentId,
                                              @PathVariable int termVersion,
                                              @RequestHeader("X-Actor-Id") String actorId,
                                              @Valid @RequestBody ArchiveDtos.RetireTermsRequest request) {
        if (termVersion != request.termVersion()) {
            throw ApiException.badRequest("路径术语版本 " + termVersion + " 与请求体术语版本 "
                    + request.termVersion() + " 不一致");
        }
        String operation = "POST /api/documents/" + documentId + "/terms/" + termVersion + "/retire";
        return writeExecutor.execute(request.requestId(),
                hash(operation, actorId, request.termVersion(), request.reason()),
                () -> WriteResult.of(200, archiveService.retireTerms(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 创建归档包：固化规范排序的语种、逐段译文版本、术语版本、审批记录与发布快照摘要。 */
    @PostMapping("/archives")
    public ResponseEntity<String> createArchive(@PathVariable long documentId,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @Valid @RequestBody ArchiveDtos.CreateArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives";
        return writeExecutor.execute(request.requestId(),
                hash(operation, actorId, request.publishedVersion()),
                () -> WriteResult.of(201, archiveService.createArchive(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 批量归档：版本集合先去重排序再做指纹与校验，任一版本不完整则整批不产生包。 */
    @PostMapping("/archives/batch")
    public ResponseEntity<String> batchArchive(@PathVariable long documentId,
                                               @RequestHeader("X-Actor-Id") String actorId,
                                               @Valid @RequestBody ArchiveDtos.BatchArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives/batch";
        List<Integer> normalizedVersions = request.publishedVersions().stream().distinct().sorted().toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("publishedVersions", normalizedVersions);
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, canonical),
                () -> WriteResult.of(201, archiveService.batchArchive(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 撤销归档包：仅标记访问失效并保留原因，证据不删除。 */
    @PostMapping("/archives/{archiveId}/revoke")
    public ResponseEntity<String> revokeArchive(@PathVariable long documentId,
                                                @PathVariable long archiveId,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @Valid @RequestBody ArchiveDtos.RevokeArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives/" + archiveId + "/revoke";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request.reason()),
                () -> WriteResult.of(200,
                        archiveService.revokeArchive(documentId, archiveId, actorId, request)))
                .toResponseEntity();
    }

    /** 查询单个归档包明细；ACTIVE 返回证据，REVOKED 返回 410。 */
    @GetMapping("/archives/{archiveId}")
    public ResponseEntity<ArchiveDtos.ArchiveDetailResponse> getArchive(@PathVariable long documentId,
                                                                        @PathVariable long archiveId) {
        return ResponseEntity.ok(archiveService.getArchive(documentId, archiveId));
    }

    /** 查询归档历史：from/to 为 UTC ISO-8601，区间左闭右开，按生成时刻与归档 ID 稳定升序。 */
    @GetMapping("/archives")
    public ResponseEntity<ArchiveDtos.ArchiveHistoryResponse> listArchives(
            @PathVariable long documentId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ResponseEntity.ok(archiveService.listArchives(documentId, from, to));
    }

    /** 归档诊断：要求值、实际值、缺额与全部问题原因。 */
    @GetMapping("/releases/{publishedVersion}/archive-diagnostics")
    public ResponseEntity<ArchiveDtos.ArchiveDiagnosticsResponse> getDiagnostics(
            @PathVariable long documentId, @PathVariable int publishedVersion) {
        return ResponseEntity.ok(archiveService.getDiagnostics(documentId, publishedVersion));
    }

    /** 计算请求摘要：操作（含路径变量）+ 操作者 + 规范化字段的 SHA-256；集合按 JSON 数组序列化，不使用裸分隔符拼接。 */
    private String hash(String operation, Object... parts) {
        try {
            StringBuilder canonical = new StringBuilder(operation);
            for (Object part : parts) {
                canonical.append('\n').append(objectMapper.writeValueAsString(part));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
