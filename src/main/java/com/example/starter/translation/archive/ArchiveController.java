package com.example.starter.translation.archive;

import com.example.starter.translation.archive.ArchiveDtos.ArchiveDetailView;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDiagnosticResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveHistoryResponse;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveResponse;
import com.example.starter.translation.archive.ArchiveDtos.CreateArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.ReleaseSummaryView;
import com.example.starter.translation.archive.ArchiveDtos.RevokeArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.WithdrawReleaseRequest;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 译文归档包 REST API：归档创建/批量、发布撤回、归档撤销及明细/历史/诊断查询。
 * 写操作均携带 requestId 幂等；幂等指纹覆盖操作者、操作类型与规范化对象集合。
 */
@RestController
@RequestMapping("/api/documents/{documentId}")
public class ArchiveController {

    private final ArchiveService archiveService;
    private final ArchiveFingerprint fingerprint;
    private final WriteExecutor writeExecutor;

    public ArchiveController(ArchiveService archiveService, ArchiveFingerprint fingerprint,
                             WriteExecutor writeExecutor) {
        this.archiveService = archiveService;
        this.fingerprint = fingerprint;
        this.writeExecutor = writeExecutor;
    }

    /** 创建单个归档包：引用一个已发布版本，目标语种缺段或版本不一致整次 422。 */
    @PostMapping("/archives")
    public ResponseEntity<String> createArchive(@PathVariable long documentId,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @Valid @RequestBody CreateArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives";
        return writeExecutor.execute(request.requestId(),
                fingerprint.single(operation, actorId, request.publishedVersion(), request.targetLanguages()),
                () -> WriteResult.of(201, archiveService.createArchive(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 批量归档：任一语言不完整整批失败，不产生部分包。 */
    @PostMapping("/archives/batch")
    public ResponseEntity<String> batchArchive(@PathVariable long documentId,
                                               @RequestHeader("X-Actor-Id") String actorId,
                                               @Valid @RequestBody BatchArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives/batch";
        return writeExecutor.execute(request.requestId(), fingerprint.batch(operation, actorId, request),
                () -> WriteResult.of(201, archiveService.batchCreateArchives(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 撤回发布：撤回后不能新建归档包，历史证据保留。 */
    @PostMapping("/releases/{publishedVersion}/withdraw")
    public ResponseEntity<String> withdrawRelease(@PathVariable long documentId,
                                                  @PathVariable int publishedVersion,
                                                  @RequestHeader("X-Actor-Id") String actorId,
                                                  @Valid @RequestBody WithdrawReleaseRequest request) {
        String operation = "POST /api/documents/" + documentId + "/releases/" + publishedVersion + "/withdraw";
        return writeExecutor.execute(request.requestId(),
                fingerprint.action(operation, actorId, publishedVersion, request.reason()),
                () -> WriteResult.of(200,
                        archiveService.withdrawRelease(documentId, publishedVersion, actorId, request)))
                .toResponseEntity();
    }

    /** 撤销归档：仅标记访问失效并保留原因，证据不改写。 */
    @PostMapping("/archives/{archiveVersion}/revoke")
    public ResponseEntity<String> revokeArchive(@PathVariable long documentId,
                                                @PathVariable int archiveVersion,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @Valid @RequestBody RevokeArchiveRequest request) {
        String operation = "POST /api/documents/" + documentId + "/archives/" + archiveVersion + "/revoke";
        return writeExecutor.execute(request.requestId(),
                fingerprint.action(operation, actorId, archiveVersion, request.reason()),
                () -> WriteResult.of(200,
                        archiveService.revokeArchive(documentId, archiveVersion, actorId, request)))
                .toResponseEntity();
    }

    /** 查询归档包明细；已撤销返回 410，历史证据可经历史/诊断查询。 */
    @GetMapping("/archives/{archiveVersion}")
    public ResponseEntity<ArchiveDetailView> getArchive(@PathVariable long documentId,
                                                        @PathVariable int archiveVersion) {
        return ResponseEntity.ok(archiveService.getArchive(documentId, archiveVersion));
    }

    /** 查询归档领域事件历史，按提交顺序稳定排序。 */
    @GetMapping("/archive-history")
    public ResponseEntity<ArchiveHistoryResponse> getHistory(@PathVariable long documentId) {
        return ResponseEntity.ok(archiveService.getHistory(documentId));
    }

    /** 诊断查询：可按发布版本与 UTC 左闭右开时间区间过滤，返回实际值/要求值/差额。 */
    @GetMapping("/archive-diagnostics")
    public ResponseEntity<ArchiveDiagnosticResponse> diagnose(
            @PathVariable long documentId,
            @RequestParam(name = "publishedVersion", required = false) Integer publishedVersion,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(archiveService.diagnose(documentId, publishedVersion, from, to));
    }
}
