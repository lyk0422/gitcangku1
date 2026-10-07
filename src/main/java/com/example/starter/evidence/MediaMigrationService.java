package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MediaView;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationVerifyRequest;
import com.example.starter.evidence.dto.MigrationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 介质迁移核心服务。证物更换存储介质时先验证新副本再作废旧副本，不改变保管人。
 * 所有写操作在单事务内完成：先锁定证物行（SELECT ... FOR UPDATE），再校验状态机并落库，
 * 最后写入幂等日志；业务与去重原子提交。介质与迁移记录只追加，历史元数据保留。
 */
@Service
public class MediaMigrationService {

    static final String OP_MEDIA_REGISTER = "MEDIA_REGISTER";
    static final String OP_MIGRATION_CREATE = "MIGRATION_CREATE";
    static final String OP_MIGRATION_VERIFY = "MIGRATION_VERIFY";
    static final String OP_MIGRATION_SWITCH = "MIGRATION_SWITCH";
    static final String OP_MIGRATION_CANCEL = "MIGRATION_CANCEL";

    private final EvidenceRepository evidenceRepository;
    private final EvidenceMediaRepository mediaRepository;
    private final MediaMigrationRepository migrationRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public MediaMigrationService(EvidenceRepository evidenceRepository,
                                 EvidenceMediaRepository mediaRepository,
                                 MediaMigrationRepository migrationRepository,
                                 CommandLogRepository commandLogRepository,
                                 ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.mediaRepository = mediaRepository;
        this.migrationRepository = migrationRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记首个介质：仅当前保管人；证物已有介质时须走迁移。登记即为 ACTIVE，
     * 保存合成内容的 SHA-256。
     */
    @Transactional
    public StoredResponse registerMedia(String actorId, String evidenceKey,
                                        MediaRegisterRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireCustodian(evidence, actorId);
        if (!mediaRepository.findByEvidenceKey(evidenceKey).isEmpty()) {
            throw ApiException.conflict("证物已存在介质，新增介质须通过迁移: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.insert(evidenceKey, request.mediaNo(), request.sha256(),
                MediaStatus.ACTIVE, now);
        EvidenceMedia media = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, request.mediaNo()).orElseThrow();
        return record(request.commandKey(), OP_MEDIA_REGISTER, actorId, requestHash,
                201, toView(media));
    }

    /**
     * 创建迁移：仅当前保管人，证物须为正常 SEALED 且无借出、无待交接；
     * 固定当前 ACTIVE 源介质版本与新介质号，每件证物至多一条待完成迁移。
     * 新介质收到合成内容后仅为 UNVERIFIED。
     */
    @Transactional
    public StoredResponse createMigration(String actorId, String evidenceKey,
                                          MigrationCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireCustodian(evidence, actorId);
        requireNormalSealed(evidence);
        EvidenceMedia source = mediaRepository.findActiveByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.conflict("证物不存在 ACTIVE 介质，无法创建迁移: " + evidenceKey));
        if (migrationRepository.findPendingByEvidenceKey(evidenceKey).isPresent()) {
            throw ApiException.conflict("证物已存在待完成迁移: " + evidenceKey);
        }
        if (mediaRepository.findByEvidenceKeyAndMediaNo(evidenceKey, request.targetMediaNo())
                .isPresent()) {
            throw ApiException.conflict("介质编号已存在: " + request.targetMediaNo());
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.insert(evidenceKey, request.targetMediaNo(), request.targetSha256(),
                MediaStatus.UNVERIFIED, now);
        EvidenceMedia target = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, request.targetMediaNo()).orElseThrow();
        migrationRepository.insert(evidenceKey, source.id(), target.id(), actorId, now);
        MediaMigration migration = migrationRepository.findPendingByEvidenceKey(evidenceKey)
                .orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_CREATE, actorId, requestHash,
                201, toView(migration, source, target));
    }

    /**
     * 见证验证：仅非保管人见证者；提交的新介质完整摘要必须等于源摘要才 VERIFIED，
     * 不一致返回 422 且可另键重新提交。封条异常（SEAL_BROKEN）阻止验证。
     */
    @Transactional
    public StoredResponse verifyMigration(String actorId, String evidenceKey,
                                          MigrationVerifyRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requirePendingMigration(evidenceKey);
        if (evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("保管人不能自证，须由非保管人见证者提交摘要: " + actorId);
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，阻止迁移验证: " + evidenceKey);
        }
        EvidenceMedia source = mediaRepository.findById(migration.sourceMediaId()).orElseThrow();
        EvidenceMedia target = mediaRepository.findById(migration.targetMediaId()).orElseThrow();
        if (!request.sha256().equals(source.sha256())) {
            throw ApiException.unprocessable("提交摘要与源介质摘要不一致，验证失败");
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.markVerified(target.id(), request.sha256(), now);
        if (!migrationRepository.recordWitness(migration.id(), actorId)) {
            throw ApiException.conflict("迁移已被并发决定: " + evidenceKey);
        }
        MediaMigration updated = migrationRepository.findPendingByEvidenceKey(evidenceKey)
                .orElseThrow();
        EvidenceMedia verified = mediaRepository.findById(target.id()).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_VERIFY, actorId, requestHash,
                200, toView(updated, source, verified));
    }

    /**
     * 显式切换：仅当前保管人；新介质须已 VERIFIED。同一事务内新介质转 ACTIVE、
     * 旧介质转 RETIRED，保证恰好一个 ACTIVE；切换冻结两介质摘要和见证人。
     * 封条异常（SEAL_BROKEN）阻止切换。
     */
    @Transactional
    public StoredResponse switchMigration(String actorId, String evidenceKey,
                                          CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requirePendingMigration(evidenceKey);
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，阻止介质切换: " + evidenceKey);
        }
        EvidenceMedia source = mediaRepository.findById(migration.sourceMediaId()).orElseThrow();
        EvidenceMedia target = mediaRepository.findById(migration.targetMediaId()).orElseThrow();
        if (target.status() != MediaStatus.VERIFIED) {
            throw ApiException.conflict("新介质未通过见证验证，禁止切换: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.updateStatus(target.id(), MediaStatus.ACTIVE, now);
        mediaRepository.updateStatus(source.id(), MediaStatus.RETIRED, now);
        if (!migrationRepository.complete(migration.id(), source.sha256(), target.sha256(), now)) {
            throw ApiException.conflict("迁移已被并发决定: " + evidenceKey);
        }
        MediaMigration completed = migrationRepository.findByEvidenceKey(evidenceKey).stream()
                .filter(m -> m.id().equals(migration.id())).findFirst().orElseThrow();
        EvidenceMedia activated = mediaRepository.findById(target.id()).orElseThrow();
        EvidenceMedia retired = mediaRepository.findById(source.id()).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_SWITCH, actorId, requestHash,
                200, toView(completed, retired, activated));
    }

    /**
     * 取消迁移：仅当前保管人；取消不改变源副本（源介质保持 ACTIVE），
     * 已取消迁移不可被旧成功验证重放复活。
     */
    @Transactional
    public StoredResponse cancelMigration(String actorId, String evidenceKey,
                                          CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requirePendingMigration(evidenceKey);
        requireCustodian(evidence, actorId);
        LocalDateTime now = LocalDateTime.now();
        if (!migrationRepository.cancel(migration.id(), now)) {
            throw ApiException.conflict("迁移已被并发决定: " + evidenceKey);
        }
        MediaMigration cancelled = migrationRepository.findByEvidenceKey(evidenceKey).stream()
                .filter(m -> m.id().equals(migration.id())).findFirst().orElseThrow();
        EvidenceMedia source = mediaRepository.findById(migration.sourceMediaId()).orElseThrow();
        EvidenceMedia target = mediaRepository.findById(migration.targetMediaId()).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_CANCEL, actorId, requestHash,
                200, toView(cancelled, source, target));
    }

    /**
     * 查询证物全部介质（含 RETIRED 历史，按登记顺序）。
     */
    @Transactional(readOnly = true)
    public List<MediaView> listMedia(String evidenceKey) {
        requireEvidence(evidenceKey);
        return mediaRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 查询当前读取来源：仅返回 ACTIVE 介质；RETIRED 内容不可作为当前读取来源。
     */
    @Transactional(readOnly = true)
    public MediaView currentMedia(String evidenceKey) {
        requireEvidence(evidenceKey);
        return mediaRepository.findActiveByEvidenceKey(evidenceKey)
                .map(this::toView)
                .orElseThrow(() -> ApiException.notFound("证物不存在 ACTIVE 介质: " + evidenceKey));
    }

    /**
     * 按发生顺序查询证物全部迁移记录。
     */
    @Transactional(readOnly = true)
    public List<MigrationView> listMigrations(String evidenceKey) {
        requireEvidence(evidenceKey);
        Map<Long, EvidenceMedia> mediaById = mediaRepository.findByEvidenceKey(evidenceKey)
                .stream().collect(Collectors.toMap(EvidenceMedia::id, Function.identity()));
        return migrationRepository.findByEvidenceKey(evidenceKey).stream()
                .map(m -> toView(m, mediaById.get(m.sourceMediaId()), mediaById.get(m.targetMediaId())))
                .toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private Evidence requireEvidence(String evidenceKey) {
        return evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private void requireNormalSealed(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止创建介质迁移: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止创建介质迁移: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止创建介质迁移: " + evidence.evidenceKey());
        }
    }

    private MediaMigration requirePendingMigration(String evidenceKey) {
        return migrationRepository.findPendingByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.conflict("证物不存在待完成迁移: " + evidenceKey));
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private MediaView toView(EvidenceMedia media) {
        return new MediaView(media.evidenceKey(), media.mediaNo(), media.sha256(), media.status(),
                media.createdAt(), media.updatedAt());
    }

    private MigrationView toView(MediaMigration migration, EvidenceMedia source,
                                 EvidenceMedia target) {
        return new MigrationView(migration.evidenceKey(), source.mediaNo(), target.mediaNo(),
                migration.status(), migration.createdBy(), migration.witnessId(),
                migration.sourceSha256(), migration.targetSha256(),
                migration.createdAt(), migration.decidedAt());
    }
}
