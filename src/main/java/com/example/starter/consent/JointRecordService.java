package com.example.starter.consent;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.JointRecordRepository.JointRecordRow;
import com.example.starter.consent.dto.JointRecordConfirmRequest;
import com.example.starter.consent.dto.JointRecordCreateRequest;
import com.example.starter.consent.dto.JointRecordReadResponse;
import com.example.starter.consent.dto.JointRecordResponse;
import com.example.starter.consent.dto.JointRecordReviseRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 共同拥有记录域服务：两个合成主体共同提供的研究记录，需双方确认同一提案版本后方可读取。
 *
 * <p>核心规则：提案版本从 1 起，内容修订加一并清空旧确认，最多修订 3 次；
 * 修订、确认、读取均核验冻结的两方授权代次当前有效，任一撤回整体 410；
 * 写响应只含提案元数据，含值读取与成功重放每次先核验两方当前有效性。
 *
 * <p>幂等规则：全局 requestId 结构化比较操作、资源、全参数及期望版本（整数按值、文本原样）；
 * 同参重放返回首次完整结果，改参 409，失败不占键，业务与去重原子提交。
 */
@Service
public class JointRecordService {

    static final String CODE_JOINT_RECORD_NOT_FOUND = "JOINT_RECORD_NOT_FOUND";
    static final String CODE_JOINT_RECORD_CONFLICT = "JOINT_RECORD_CONFLICT";
    static final String CODE_PROPOSAL_NOT_CONFIRMED = "PROPOSAL_NOT_CONFIRMED";
    static final String CODE_PROPOSAL_VERSION_CONFLICT = "PROPOSAL_VERSION_CONFLICT";
    static final String CODE_REVISION_LIMIT_EXCEEDED = "REVISION_LIMIT_EXCEEDED";
    static final String CODE_ACTOR_FORBIDDEN = "ACTOR_FORBIDDEN";
    static final String CODE_SUBJECT_PAIR_INVALID = "SUBJECT_PAIR_INVALID";
    static final String CODE_GRANT_EPOCH_MISMATCH = "GRANT_EPOCH_MISMATCH";

    static final int MAX_REVISIONS = 3;

    private static final String OP_JOINT_CREATE = "JOINT_CREATE";
    private static final String OP_JOINT_REVISE = "JOINT_REVISE";
    private static final String OP_JOINT_CONFIRM = "JOINT_CONFIRM";

    private final JointRecordRepository jointRecordRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public JointRecordService(JointRecordRepository jointRecordRepository,
                              ConsentRepository consentRepository,
                              IdempotencyRepository idempotencyRepository,
                              ObjectMapper objectMapper) {
        this.jointRecordRepository = jointRecordRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增共同记录：指定两个不同主体的当前研究代次与合成内容，生成版本 1 提案（待双确认）。
     */
    @Transactional
    public JointRecordResponse create(String operator, JointRecordCreateRequest request) {
        requireOperator(operator);
        if (request.subjectKeyA().equals(request.subjectKeyB())) {
            throw ApiException.badRequest(CODE_SUBJECT_PAIR_INVALID, "共同记录两个主体必须不同");
        }
        // 主体对按字典序归一，正序倒序入参等价
        boolean swapped = request.subjectKeyA().compareTo(request.subjectKeyB()) > 0;
        String subjectA = swapped ? request.subjectKeyB() : request.subjectKeyA();
        String subjectB = swapped ? request.subjectKeyA() : request.subjectKeyB();
        int epochA = swapped ? request.epochB() : request.epochA();
        int epochB = swapped ? request.epochA() : request.epochB();
        String fingerprint = fingerprint(OP_JOINT_CREATE, request.recordKey(), subjectA, subjectB,
                epochA, epochB, request.payload(), operator);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return replayValue(replayed.get().responseBody());
        }
        requireCurrentEpoch(subjectA, epochA);
        requireCurrentEpoch(subjectB, epochB);
        try {
            jointRecordRepository.insert(request.recordKey(), subjectA, subjectB, epochA, epochB,
                    request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发新增同一 recordKey：以已提交行为准，参数一致返回原结果，不一致 409
            JointRecordRow committed = jointRecordRepository.find(request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!(committed.subjectA().equals(subjectA) && committed.subjectB().equals(subjectB)
                    && committed.epochA() == epochA && committed.epochB() == epochB
                    && committed.payload().equals(request.payload()))) {
                throw ApiException.conflict(CODE_JOINT_RECORD_CONFLICT, "相同 recordKey 的共同记录已存在且参数不同");
            }
            JointRecordResponse response = toResponse(committed);
            storeSuccess(request.requestId(), OP_JOINT_CREATE, fingerprint, response);
            return response;
        }
        JointRecordResponse response = toResponse(findOrThrow(request.recordKey()));
        storeSuccess(request.requestId(), OP_JOINT_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 确认当前提案版本：确认者必须声明共同记录一方主体且操作者与声明主体一致；
     * 两票齐全时一次性冻结内容与主体代次，提案生效。
     */
    @Transactional
    public JointRecordResponse confirm(String operator, String recordKey, JointRecordConfirmRequest request) {
        requireOperator(operator);
        String fingerprint = fingerprint(OP_JOINT_CONFIRM, recordKey, request.subjectKey(),
                request.expectedVersion(), operator);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return replayValue(replayed.get().responseBody());
        }
        JointRecordRow row = findOrThrow(recordKey);
        boolean isSubjectA;
        if (request.subjectKey().equals(row.subjectA())) {
            isSubjectA = true;
        } else if (request.subjectKey().equals(row.subjectB())) {
            isSubjectA = false;
        } else {
            throw ApiException.forbidden(CODE_ACTOR_FORBIDDEN, "声明主体不属于该共同记录");
        }
        if (!operator.equals(request.subjectKey())) {
            throw ApiException.forbidden(CODE_ACTOR_FORBIDDEN, "操作者与声明主体不一致");
        }
        requireBothActive(row);
        if (request.expectedVersion() != row.version()) {
            throw ApiException.conflict(CODE_PROPOSAL_VERSION_CONFLICT, "提案版本已变化，确认失效");
        }
        boolean alreadyConfirmed = isSubjectA ? row.confirmA() : row.confirmB();
        if (!alreadyConfirmed) {
            jointRecordRepository.confirm(recordKey, row.version(), isSubjectA);
            jointRecordRepository.promoteIfConfirmed(recordKey);
        }
        JointRecordResponse response = toResponse(findOrThrow(recordKey));
        storeSuccess(request.requestId(), OP_JOINT_CONFIRM, fingerprint, response);
        return response;
    }

    /**
     * 内容修订：版本加一并清空双方旧确认，回到待双确认；最多修订 3 次，超限 422。
     */
    @Transactional
    public JointRecordResponse revise(String operator, String recordKey, JointRecordReviseRequest request) {
        requireOperator(operator);
        String fingerprint = fingerprint(OP_JOINT_REVISE, recordKey, request.expectedVersion(),
                request.payload(), operator);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return replayValue(replayed.get().responseBody());
        }
        JointRecordRow row = findOrThrow(recordKey);
        requireBothActive(row);
        if (request.expectedVersion() != row.version()) {
            throw ApiException.conflict(CODE_PROPOSAL_VERSION_CONFLICT, "提案版本已变化，修订失效");
        }
        if (row.revisionCount() >= MAX_REVISIONS) {
            throw ApiException.unprocessableEntity(CODE_REVISION_LIMIT_EXCEEDED, "内容修订次数已达上限");
        }
        boolean revised = jointRecordRepository.revise(recordKey, request.expectedVersion(),
                request.payload(), MAX_REVISIONS);
        if (!revised) {
            // 并发修订竞争：以已提交行为准，区分版本冲突与修订上限
            JointRecordRow committed = findOrThrow(recordKey);
            if (committed.revisionCount() >= MAX_REVISIONS) {
                throw ApiException.unprocessableEntity(CODE_REVISION_LIMIT_EXCEEDED, "内容修订次数已达上限");
            }
            throw ApiException.conflict(CODE_PROPOSAL_VERSION_CONFLICT, "提案版本已变化，修订失效");
        }
        JointRecordResponse response = toResponse(findOrThrow(recordKey));
        storeSuccess(request.requestId(), OP_JOINT_REVISE, fingerprint, response);
        return response;
    }

    /**
     * 读取：两代授权均须当前有效（任一撤回 410），当前提案未双确认返回 409。
     */
    @Transactional(readOnly = true)
    public JointRecordReadResponse read(String recordKey) {
        JointRecordRow row = findOrThrow(recordKey);
        requireBothActive(row);
        if (row.status() != JointStatus.CONFIRMED) {
            throw ApiException.conflict(CODE_PROPOSAL_NOT_CONFIRMED, "当前提案尚未双方确认");
        }
        return new JointRecordReadResponse(row.recordKey(), row.subjectA(), row.subjectB(),
                row.epochA(), row.epochB(), row.version(), row.status(), row.payload());
    }

    private JointRecordRow findOrThrow(String recordKey) {
        return jointRecordRepository.find(recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_JOINT_RECORD_NOT_FOUND, "共同记录不存在"));
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.forbidden(CODE_ACTOR_FORBIDDEN, "操作者标识缺失");
        }
    }

    /**
     * 校验指定代次即为主体当前有效研究代次：无授权 404，已撤回 410，代次不一致 409。
     */
    private void requireCurrentEpoch(String subjectKey, int epoch) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (latest.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回");
        }
        if (latest.epoch() != epoch) {
            throw ApiException.conflict(CODE_GRANT_EPOCH_MISMATCH, "指定代次不是当前有效代次");
        }
    }

    /**
     * 核验共同记录冻结的两方代次当前均有效；任一已撤回则整体 410。
     */
    private void requireBothActive(JointRecordRow row) {
        requireGrantActive(row.subjectA(), row.epochA());
        requireGrantActive(row.subjectB(), row.epochB());
    }

    private void requireGrantActive(String subjectKey, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 含值重放前先核验两方当前有效性：任一已撤回即使命中幂等快照也返回 410。
     */
    private JointRecordResponse replayValue(String responseBody) {
        JointRecordResponse snapshot = readSnapshot(responseBody, JointRecordResponse.class);
        requireGrantActive(snapshot.subjectA(), snapshot.epochA());
        requireGrantActive(snapshot.subjectB(), snapshot.epochB());
        return snapshot;
    }

    /**
     * 结构化参数指纹：操作、资源、全参数及期望版本按位拼接；整数按值、文本原样。
     */
    private String fingerprint(String operation, Object... parts) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object part : parts) {
            sb.append('|').append(part);
        }
        return sb.toString();
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private JointRecordResponse toResponse(JointRecordRow row) {
        return new JointRecordResponse(row.recordKey(), row.subjectA(), row.subjectB(),
                row.epochA(), row.epochB(), row.version(), row.revisionCount(), row.status(),
                row.confirmA(), row.confirmB());
    }

    private String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
