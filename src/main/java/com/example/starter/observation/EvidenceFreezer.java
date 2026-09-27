package com.example.starter.observation;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 观测版本证据固化：观测创建、合并、人工解决、删除生成新版本时，
 * 逐附件固化当时最新有效（ACTIVE）脱敏版本号到 observation_evidence_ref。
 * 固化行永不回写：后续脱敏、撤销、物理清除均不追溯替换既有证据引用。
 */
@Component
public class EvidenceFreezer {

    private final AttachmentRepository attachmentRepository;
    private final AttachmentRedactionRepository redactionRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final Clock clock;

    public EvidenceFreezer(AttachmentRepository attachmentRepository,
                           AttachmentRedactionRepository redactionRepository,
                           EvidenceRefRepository evidenceRefRepository,
                           Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.redactionRepository = redactionRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.clock = clock;
    }

    /**
     * 为指定观测版本固化全部附件的证据引用；须在生成该版本的同一事务内调用。
     * 当时无有效脱敏版本的附件固化 redactionVersion 为 null（快照公开原始摘要）。
     */
    public void freezeForVersion(String observationId, int observationVersion) {
        List<AttachmentRecord> attachments = attachmentRepository.findByObservationId(observationId);
        if (attachments.isEmpty()) {
            return;
        }
        Instant frozenAtUtc = Instant.now(clock);
        for (AttachmentRecord attachment : attachments) {
            Integer redactionVersion = redactionRepository.findLatestActive(attachment.attachmentId())
                    .map(AttachmentRedactionRecord::redactionVersion)
                    .orElse(null);
            evidenceRefRepository.insert(new EvidenceRefRecord(observationId, observationVersion,
                    attachment.attachmentId(), redactionVersion, frozenAtUtc));
        }
    }
}
