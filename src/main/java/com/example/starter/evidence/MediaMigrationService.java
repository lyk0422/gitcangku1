package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MediaView;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationDecideRequest;
import com.example.starter.evidence.dto.MigrationVerifyRequest;
import com.example.starter.evidence.dto.MigrationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 证物介质迁移核心服务。所有写操作在单事务内完成：
 * 先锁定证物行（SELECT ... FOR UPDATE），再校验当前操作人权限（权限先于幂等重放），
 * 然后处理幂等重放，最后按状态机与期望版本落库并写入幂等日志。
 * 切换时源介质 RETIRED、新介质 ACTIVE 同事务提交，保证每件证物恰好一个 ACTIVE；
 * 取消不改变源副本。RETIRED 介质仅保留历史元数据，不作为当前读取来源。
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
     * 登记介质：仅当前保管人；证物尚无 ACTIVE 介质时登记即为当前读取来源（ACTIVE）。
     * 已存在 ACTIVE 介质时拒绝，新副本只能经由迁移引入。
     */
    @Transactional
    public StoredResponse registerMedia(String actorId, String evidenceKey,
                                        MediaRegisterRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (mediaRepository.findActiveByEvidenceKey(evidenceKey).isPresent()) {
            throw ApiException.conflict("证物已存在 ACTIVE 介质，新副本须经由迁移引入: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.insert(evidenceKey, request.mediaNo(), request.sha256(),
                MediaStatus.ACTIVE, actorId, now);
        EvidenceMedia media = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, request.mediaNo()).orElseThrow();
        return record(request.commandKey(), OP_MEDIA_REGISTER, actorId, requestHash,
                201, toView(media));
    }

    /**
     * 创建迁移：仅当前保管人，证物须为 SEALED（无借出、无待交接），且已存在 ACTIVE 源介质；
     * 每件证物至多一条待完成迁移。创建时固定源介质版本与新介质号，新介质仅为 UNVERIFIED。
     */
    @Transactional
    public StoredResponse createMigration(String actorId, String evidenceKey,
                                          MigrationCreateRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止创建介质迁移: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止创建介质迁移: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止创建介质迁移: " + evidenceKey);
        }
        EvidenceMedia source = mediaRepository.findActiveByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.conflict("证物不存在 ACTIVE 源介质: " + evidenceKey));
        if (migrationRepository.findActiveByEvidenceKey(evidenceKey).isPresent()) {
            throw ApiException.conflict("证物已存在待完成迁移: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        mediaRepository.insert(evidenceKey, request.targetMediaNo(), request.targetSha256(),
                MediaStatus.UNVERIFIED, actorId, now);
        EvidenceMedia target = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, request.targetMediaNo()).orElseThrow();
        migrationRepository.insert(request.migrationKey(), evidenceKey,
                source.id(), source.mediaNo(), source.version(),
                target.id(), target.mediaNo(), actorId, now);
        MediaMigration migration = migrationRepository.findByKey(request.migrationKey()).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_CREATE, actorId, requestHash,
                201, toView(migration));
    }

    /**
     * 见证验证：仅非保管人见证者可提交（当前权限先于幂等重放校验）。
     * 提交的新介质完整摘要必须等于源摘要，相等则新介质 VERIFIED、迁移进入 PENDING_SWITCH；
     * 不等返回 422，迁移保持待验证，可换键重新提交。封条异常（SEAL_BROKEN）阻止验证。
     */
    @Transactional
    public StoredResponse verifyMigration(String actorId, String evidenceKey, String migrationKey,
                                          MigrationVerifyRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        // 当前权限先于重放：保管人不能作为自己的见证者
        if (evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("保管人不能作为迁移见证者: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requireMigration(evidenceKey, migrationKey);
        if (migration.status() != MigrationStatus.PENDING_VERIFY) {
            throw ApiException.conflict("迁移不在待验证状态: " + migrationKey);
        }
        requireVersion(migration, request.expectedVersion());
        requireSealIntact(evidence);
        EvidenceMedia source = mediaRepository.findActiveByEvidenceKey(evidenceKey)
                .filter(media -> media.id().equals(migration.sourceMediaId()))
                .orElseThrow(() -> ApiException.conflict("迁移源介质已不是当前 ACTIVE 介质: " + migrationKey));
        if (!source.sha256().equals(request.digest())) {
            throw ApiException.unprocessable("见证摘要与源摘要不一致: " + migrationKey);
        }
        if (!migrationRepository.markVerified(migration.id(), migration.version(), actorId)) {
            throw ApiException.conflict("迁移已被并发流转: " + migrationKey);
        }
        EvidenceMedia target = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, migration.targetMediaNo()).orElseThrow();
        if (!mediaRepository.transition(target.id(), MediaStatus.UNVERIFIED, MediaStatus.VERIFIED, null)) {
            throw ApiException.conflict("新介质状态已被并发改变: " + migration.targetMediaNo());
        }
        MediaMigration updated = migrationRepository.findByKey(migrationKey).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_VERIFY, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 保管人显式切换：仅当前保管人，迁移须为 PENDING_SWITCH 且封条完好。
     * 同事务内新介质转 ACTIVE、源介质转 RETIRED，保证恰好一个 ACTIVE；
     * 切换后两介质摘要与见证人冻结不可变。
     */
    @Transactional
    public StoredResponse switchMigration(String actorId, String evidenceKey, String migrationKey,
                                          MigrationDecideRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requireMigration(evidenceKey, migrationKey);
        if (migration.status() != MigrationStatus.PENDING_SWITCH) {
            throw ApiException.conflict("迁移不在待切换状态: " + migrationKey);
        }
        requireVersion(migration, request.expectedVersion());
        requireSealIntact(evidence);
        LocalDateTime now = LocalDateTime.now();
        if (!migrationRepository.decide(migration.id(), migration.version(),
                MigrationStatus.PENDING_SWITCH, MigrationStatus.SWITCHED, now)) {
            throw ApiException.conflict("迁移已被并发决定: " + migrationKey);
        }
        EvidenceMedia source = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, migration.sourceMediaNo()).orElseThrow();
        EvidenceMedia target = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, migration.targetMediaNo()).orElseThrow();
        if (!mediaRepository.transition(source.id(), MediaStatus.ACTIVE, MediaStatus.RETIRED, now)) {
            throw ApiException.conflict("源介质状态已被并发改变: " + migration.sourceMediaNo());
        }
        if (!mediaRepository.transition(target.id(), MediaStatus.VERIFIED, MediaStatus.ACTIVE, null)) {
            throw ApiException.conflict("新介质状态已被并发改变: " + migration.targetMediaNo());
        }
        MediaMigration updated = migrationRepository.findByKey(migrationKey).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_SWITCH, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 取消迁移：仅当前保管人；源副本保持 ACTIVE 不变，新介质作废为 RETIRED。
     * 封条异常不阻止取消；已完结（SWITCHED/CANCELLED）迁移不可再取消。
     */
    @Transactional
    public StoredResponse cancelMigration(String actorId, String evidenceKey, String migrationKey,
                                          MigrationDecideRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MediaMigration migration = requireMigration(evidenceKey, migrationKey);
        if (migration.status() != MigrationStatus.PENDING_VERIFY
                && migration.status() != MigrationStatus.PENDING_SWITCH) {
            throw ApiException.conflict("迁移已完结，不可取消: " + migrationKey);
        }
        requireVersion(migration, request.expectedVersion());
        LocalDateTime now = LocalDateTime.now();
        if (!migrationRepository.cancel(migration.id(), migration.version(), now)) {
            throw ApiException.conflict("迁移已被并发决定: " + migrationKey);
        }
        EvidenceMedia target = mediaRepository
                .findByEvidenceKeyAndMediaNo(evidenceKey, migration.targetMediaNo()).orElseThrow();
        if (!mediaRepository.transition(target.id(), target.status(), MediaStatus.RETIRED, now)) {
            throw ApiException.conflict("新介质状态已被并发改变: " + migration.targetMediaNo());
        }
        MediaMigration updated = migrationRepository.findByKey(migrationKey).orElseThrow();
        return record(request.commandKey(), OP_MIGRATION_CANCEL, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 查询证物全部介质（含 RETIRED 历史元数据，按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<MediaView> listMedia(String evidenceKey) {
        requireEvidence(evidenceKey);
        return mediaRepository.findByEvidenceKey(evidenceKey).stream().map(this::toView).toList();
    }

    /**
     * 查询当前读取来源（唯一 ACTIVE 介质）；RETIRED 介质不作为当前读取来源。
     */
    @Transactional(readOnly = true)
    public MediaView currentMedia(String evidenceKey) {
        requireEvidence(evidenceKey);
        return mediaRepository.findActiveByEvidenceKey(evidenceKey)
                .map(this::toView)
                .orElseThrow(() -> ApiException.notFound("证物不存在 ACTIVE 介质: " + evidenceKey));
    }

    /**
     * 查询证物全部迁移记录（按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<MigrationView> listMigrations(String evidenceKey) {
        requireEvidence(evidenceKey);
        return migrationRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireEvidence(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private void requireSealIntact(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，阻止验证及切换: " + evidence.evidenceKey());
        }
    }

    private MediaMigration requireMigration(String evidenceKey, String migrationKey) {
        MediaMigration migration = migrationRepository.findByKey(migrationKey)
                .orElseThrow(() -> ApiException.notFound("迁移不存在: " + migrationKey));
        if (!migration.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("迁移不属于该证物: " + migrationKey);
        }
        return migration;
    }

    private void requireVersion(MediaMigration migration, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != migration.version()) {
            throw ApiException.conflict("期望版本与当前迁移版本不一致: " + migration.migrationKey());
        }
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
                media.version(), media.createdBy(), media.createdAt(), media.retiredAt());
    }

    private MigrationView toView(MediaMigration migration) {
        return new MigrationView(migration.migrationKey(), migration.evidenceKey(),
                migration.sourceMediaNo(), migration.sourceVersion(), migration.targetMediaNo(),
                migration.status(), migration.witnessId(), migration.version(),
                migration.createdBy(), migration.createdAt(), migration.decidedAt());
    }
}
