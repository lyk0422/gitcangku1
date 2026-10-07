package com.example.starter.consent;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.JointRecordRepository.JointRow;
import com.example.starter.consent.dto.JointConfirmRequest;
import com.example.starter.consent.dto.JointCreateRequest;
import com.example.starter.consent.dto.JointProposalResponse;
import com.example.starter.consent.dto.JointRecordValueResponse;
import com.example.starter.consent.dto.JointReviseRequest;

/**
 * 共同研究记录服务：研究记录由两个合成主体共同提供，双方各确认同一提案版本后生效。
 *
 * <p>核心规则：创建时冻结两方当前研究代次，重新授权不替换旧记录中的代次；
 * 提案版本从 1 起，内容修订加一并清空旧确认，最多修订 3 次；确认者必须声明其中一个主体
 * 且 X-Actor-Id 等于该主体，两个确认不能由同一操作者完成；读取与含值重放每次先核验
 * 双方当前授权有效性，任一代次撤回即整体 410。写响应只返回提案元数据。
 */
@Service
public class JointRecordService {

    static final String CODE_JOINT_RECORD_NOT_FOUND = "JOINT_RECORD_NOT_FOUND";
    static final String CODE_JOINT_SUBJECTS_EQUAL = "JOINT_SUBJECTS_EQUAL";
    static final String CODE_JOINT_RECORD_KEY_CONFLICT = "JOINT_RECORD_KEY_CONFLICT";
    static final String CODE_JOINT_VERSION_CONFLICT = "JOINT_VERSION_CONFLICT";
    static final String CODE_JOINT_NOT_CONFIRMED = "JOINT_NOT_CONFIRMED";
    static final String CODE_JOINT_SAME_ACTOR = "JOINT_SAME_ACTOR";
    static final String CODE_JOINT_ACTOR_FORBIDDEN = "JOINT_ACTOR_FORBIDDEN";
    static final String CODE_JOINT_REVISION_LIMIT = "JOINT_REVISION_LIMIT";

    static final int MAX_REVISIONS = 3;

    private static final String OP_JOINT_CREATE = "JOINT_CREATE";
    private static final String OP_JOINT_CONFIRM = "JOINT_CONFIRM";
    private static final String OP_JOINT_REVISE = "JOINT_REVISE";

    private final JointRecordRepository jointRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencySupport idempotency;

    public JointRecordService(JointRecordRepository jointRepository,
                              ConsentRepository consentRepository,
                              IdempotencySupport idempotency) {
        this.jointRepository = jointRepository;
        this.consentRepository = consentRepository;
        this.idempotency = idempotency;
    }

    /**
     * 创建共同记录提案：两个主体必须不同且各持当前有效研究授权，代次在创建时冻结；
     * 同 recordKey 同参数去重返回原提案，不同参数返回 409。
     */
    @Transactional
    public JointProposalResponse create(JointCreateRequest request) {
        String fingerprint = OP_JOINT_CREATE + "|" + request.recordKey() + "|" + request.subjectA()
                + "|" + request.subjectB() + "|" + request.payload();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 含值重放前先核验双方当前授权有效性
            requireBothEpochsActive(findRecord(request.recordKey()));
            return idempotency.readSnapshot(replayed.get().responseBody(), JointProposalResponse.class);
        }
        if (request.subjectA().equals(request.subjectB())) {
            throw ApiException.badRequest(CODE_JOINT_SUBJECTS_EQUAL, "共同记录的两个主体必须不同");
        }
        GrantRow grantA = latestActiveGrant(request.subjectA());
        GrantRow grantB = latestActiveGrant(request.subjectB());

        Optional<JointRow> existing = jointRepository.find(request.recordKey());
        if (existing.isPresent()) {
            return deduplicate(existing.get(), request, fingerprint);
        }
        try {
            jointRepository.insert(request.recordKey(), request.subjectA(), grantA.epoch(),
                    request.subjectB(), grantB.epoch(), request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发创建同一 recordKey：以已提交的记录为准，不把竞争报为唯一键 500
            JointRow committed = jointRepository.find(request.recordKey())
                    .orElseThrow(() -> concurrent);
            return deduplicate(committed, request, fingerprint);
        }
        JointProposalResponse response = toResponse(findRecord(request.recordKey()));
        idempotency.storeSuccess(request.requestId(), OP_JOINT_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 确认当前提案版本：确认者必须声明记录两方之一且 X-Actor-Id 等于该主体，越权 403；
     * 两个确认不能由同一操作者完成；两票齐时一次冻结 payload 与主体代次。
     */
    @Transactional
    public JointProposalResponse confirm(String recordKey, String actorId, JointConfirmRequest request) {
        String fingerprint = OP_JOINT_CONFIRM + "|" + recordKey + "|" + request.subject()
                + "|" + request.version() + "|" + actorId;
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        JointRow row = findRecord(recordKey);
        if (replayed.isPresent()) {
            requireBothEpochsActive(row);
            return idempotency.readSnapshot(replayed.get().responseBody(), JointProposalResponse.class);
        }
        if (actorId == null || actorId.isBlank() || !actorId.equals(request.subject())) {
            throw ApiException.forbidden(CODE_JOINT_ACTOR_FORBIDDEN, "X-Actor-Id 必须等于声明主体");
        }
        requireBothEpochsActive(row);
        boolean sideA = request.subject().equals(row.subjectA());
        if (!sideA && !request.subject().equals(row.subjectB())) {
            throw ApiException.forbidden(CODE_JOINT_ACTOR_FORBIDDEN, "声明主体不属于该共同记录");
        }
        if (row.version() != request.version()) {
            throw ApiException.conflict(CODE_JOINT_VERSION_CONFLICT, "提案版本已变化");
        }
        String otherSideConfirmer = sideA ? row.confirmB() : row.confirmA();
        if (actorId.equals(otherSideConfirmer)) {
            throw ApiException.conflict(CODE_JOINT_SAME_ACTOR, "两个确认不能由同一操作者完成");
        }
        boolean alreadyConfirmed = (sideA ? row.confirmA() : row.confirmB()) != null;
        if (!alreadyConfirmed) {
            int updated = jointRepository.confirmSide(recordKey, sideA, actorId, request.version());
            if (updated == 0) {
                // 并发下提案已被修订：重新加载并按当前版本判定
                JointRow current = findRecord(recordKey);
                if (current.version() != request.version()) {
                    throw ApiException.conflict(CODE_JOINT_VERSION_CONFLICT, "提案版本已变化");
                }
            }
            jointRepository.freezeIfBothConfirmed(recordKey);
        }
        JointProposalResponse response = toResponse(findRecord(recordKey));
        idempotency.storeSuccess(request.requestId(), OP_JOINT_CONFIRM, fingerprint, response);
        return response;
    }

    /**
     * 内容修订：版本加一并清空旧确认，最多修订 3 次，超限 422；任一授权已撤回不得修订。
     */
    @Transactional
    public JointProposalResponse revise(String recordKey, JointReviseRequest request) {
        String fingerprint = OP_JOINT_REVISE + "|" + recordKey + "|" + request.expectedVersion()
                + "|" + request.payload();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        JointRow row = findRecord(recordKey);
        if (replayed.isPresent()) {
            requireBothEpochsActive(row);
            return idempotency.readSnapshot(replayed.get().responseBody(), JointProposalResponse.class);
        }
        requireBothEpochsActive(row);
        requireRevisable(row, request.expectedVersion());
        int updated = jointRepository.revise(recordKey, request.payload(),
                request.expectedVersion(), MAX_REVISIONS);
        if (updated == 0) {
            // 并发下提案状态已变化：重新加载并按当前状态判定
            requireRevisable(findRecord(recordKey), request.expectedVersion());
            throw ApiException.conflict(CODE_JOINT_VERSION_CONFLICT, "提案版本已变化");
        }
        JointProposalResponse response = toResponse(findRecord(recordKey));
        idempotency.storeSuccess(request.requestId(), OP_JOINT_REVISE, fingerprint, response);
        return response;
    }

    /**
     * 含值读取：双方冻结代次均有效且当前提案已双确认才返回内容；旧代撤回 410，未双确认 409。
     */
    @Transactional(readOnly = true)
    public JointRecordValueResponse read(String recordKey) {
        JointRow row = findRecord(recordKey);
        requireBothEpochsActive(row);
        if (row.status() != JointStatus.FROZEN) {
            throw ApiException.conflict(CODE_JOINT_NOT_CONFIRMED, "当前提案未完成双确认");
        }
        return new JointRecordValueResponse(row.recordKey(), row.subjectA(), row.epochA(),
                row.subjectB(), row.epochB(), row.version(), row.revisionCount(), row.status(),
                row.payload());
    }

    private JointProposalResponse deduplicate(JointRow row, JointCreateRequest request, String fingerprint) {
        if (!row.subjectA().equals(request.subjectA()) || !row.subjectB().equals(request.subjectB())
                || !row.payload().equals(request.payload())) {
            throw ApiException.conflict(CODE_JOINT_RECORD_KEY_CONFLICT, "相同 recordKey 已存在不同内容");
        }
        JointProposalResponse response = toResponse(row);
        idempotency.storeSuccess(request.requestId(), OP_JOINT_CREATE, fingerprint, response);
        return response;
    }

    private void requireRevisable(JointRow row, int expectedVersion) {
        if (row.version() != expectedVersion) {
            throw ApiException.conflict(CODE_JOINT_VERSION_CONFLICT, "提案版本已变化");
        }
        if (row.revisionCount() >= MAX_REVISIONS) {
            throw ApiException.unprocessableEntity(CODE_JOINT_REVISION_LIMIT, "记录修订次数已达上限 3 次");
        }
    }

    private JointRow findRecord(String recordKey) {
        return jointRepository.find(recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_JOINT_RECORD_NOT_FOUND, "共同记录不存在"));
    }

    private GrantRow latestActiveGrant(String subjectKey) {
        GrantRow latest = consentRepository.findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.epoch());
        return latest;
    }

    /**
     * 核验记录中冻结的两方代次当前均有效；任一代撤回即整体 410，重新授权的新代次不替代旧代次。
     */
    private void requireBothEpochsActive(JointRow row) {
        requireGrantActive(row.subjectA(), row.epochA());
        requireGrantActive(row.subjectB(), row.epochB());
    }

    private void requireGrantActive(String subjectKey, int epoch) {
        GrantRow grant = consentRepository.findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    private JointProposalResponse toResponse(JointRow row) {
        return new JointProposalResponse(row.recordKey(), row.subjectA(), row.epochA(),
                row.subjectB(), row.epochB(), row.version(), row.revisionCount(), row.status(),
                row.confirmA() != null, row.confirmB() != null);
    }
}
