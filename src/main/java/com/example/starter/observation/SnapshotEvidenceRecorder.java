package com.example.starter.observation;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合并快照与人工冲突选择的附件证据引用校验/固化组件。
 *
 * <p>校验规则：附件必须存在且属于当前观测（不存在 404、不归属 403）；ORIGINAL 不得携带 redactionId，
 * 且原始内容已被物理清除时无法固化（422）；REDACTED 必须指定属于该附件的脱敏版本（不存在 404、
 * 不属于该附件 403）。校验全部通过后，调用方与业务变更在同一事务内写入不可变快照行。
 */
@Component
public class SnapshotEvidenceRecorder {

    private final AttachmentRepository attachmentRepository;
    private final RedactionRepository redactionRepository;
    private final SnapshotRefRepository snapshotRefRepository;
    private final Clock clock;

    public SnapshotEvidenceRecorder(AttachmentRepository attachmentRepository,
                                    RedactionRepository redactionRepository,
                                    SnapshotRefRepository snapshotRefRepository,
                                    Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.redactionRepository = redactionRepository;
        this.snapshotRefRepository = snapshotRefRepository;
        this.clock = clock;
    }

    /**
     * 校验全部引用并解析出固化所需数据；任一失败抛出业务异常，调用方事务整体回滚。
     */
    public List<ResolvedRef> validate(String observationId, List<AttachmentRefRequest> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<ResolvedRef> resolved = new ArrayList<>();
        for (AttachmentRefRequest ref : refs) {
            // 同一附件可以分别以原始摘要和某个脱敏版本被引用，但完全相同的引用不得重复。
            String dedupKey = ref.attachmentId() + "|" + ref.kind() + "|"
                    + (ref.redactionId() == null ? "" : ref.redactionId());
            if (!seen.add(dedupKey)) {
                throw ApiException.unprocessable(
                        "duplicate attachment reference in one request: " + dedupKey);
            }
            AttachmentMeta attachment = attachmentRepository.find(ref.attachmentId())
                    .orElseThrow(() -> ApiException.notFound(
                            "attachment not found: " + ref.attachmentId()));
            if (!attachment.observationId().equals(observationId)) {
                throw ApiException.forbidden(
                        "attachment does not belong to observation " + observationId + ": " + ref.attachmentId());
            }
            if (ref.kind() == AttachmentRefKind.ORIGINAL) {
                if (ref.redactionId() != null && !ref.redactionId().isBlank()) {
                    throw ApiException.badRequest(
                            "redactionId must be empty for ORIGINAL reference: " + ref.attachmentId());
                }
                if (attachment.contentCleared()) {
                    throw ApiException.unprocessable(
                            "original content already cleared, cannot fix ORIGINAL evidence: " + ref.attachmentId());
                }
                resolved.add(new ResolvedRef(ref, attachment, null, attachment.originalSummary()));
            } else {
                if (ref.redactionId() == null || ref.redactionId().isBlank()) {
                    throw ApiException.badRequest(
                            "redactionId is required for REDACTED reference: " + ref.attachmentId());
                }
                RedactionVersion version = redactionRepository.findById(ref.redactionId())
                        .orElseThrow(() -> ApiException.notFound(
                                "redaction version not found: " + ref.redactionId()));
                if (!version.attachmentId().equals(ref.attachmentId())) {
                    throw ApiException.forbidden(
                            "redaction version does not belong to attachment " + ref.attachmentId()
                                    + ": " + ref.redactionId());
                }
                resolved.add(new ResolvedRef(ref, attachment, version, version.redactedSummary()));
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * 将已校验的引用固化为不可变快照行；必须与业务变更在同一事务内调用。
     */
    public void record(String sourceType, String sourceRequestId, String resolutionId,
                       String observationId, int observationVersion, List<ResolvedRef> resolved) {
        Instant now = Instant.now(clock);
        for (ResolvedRef item : resolved) {
            SnapshotAttachmentRef row = new SnapshotAttachmentRef(
                    null,
                    sourceType,
                    sourceRequestId,
                    resolutionId,
                    observationId,
                    observationVersion,
                    item.ref().attachmentId(),
                    item.ref().kind() == AttachmentRefKind.ORIGINAL,
                    item.version() == null ? null : item.version().redactionId(),
                    item.version() == null ? null : item.version().attachmentVersion(),
                    item.summaryText(),
                    item.attachment().contentCleared(),
                    now);
            snapshotRefRepository.insert(row);
        }
    }

    /**
     * 校验通过的单条引用：携带附件元数据、脱敏版本（ORIGINAL 时为 null）与固化摘要原文。
     */
    public record ResolvedRef(
            AttachmentRefRequest ref,
            AttachmentMeta attachment,
            RedactionVersion version,
            String summaryText) {
    }
}
