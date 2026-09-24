package com.example.starter.consent;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ExportListResponse;
import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;
import com.example.starter.consent.dto.SnapshotPurposeView;
import com.example.starter.consent.dto.SnapshotRecordItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 导出快照服务：在一个事务内读取各用途当前有效代次及全部记录，生成不可变快照。
 *
 * <p>一致性规则：生成时对各用途最新代次加行锁（FOR UPDATE），与并发撤回按提交顺序裁决——
 * 生成先提交则快照完整保留（随后读取标记 STALE），撤回先提交则生成整次 403 且无内容。
 * 快照写入后不提供任何更新或删除路径，撤回与重新授权均不改写快照。
 *
 * <p>幂等规则：requestId 同参（用途集合换序视为同参）重放返回首次响应快照，异参返回 409；
 * exportKey 全局唯一，已被占用的 exportKey 返回 409；失败请求既不占用 requestId 也不占用 exportKey。
 */
@Service
public class ExportService {

    static final String CODE_SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    static final String CODE_PURPOSE_NOT_ACTIVE = "PURPOSE_NOT_ACTIVE";
    static final String CODE_EXPORT_KEY_CONFLICT = "EXPORT_KEY_CONFLICT";
    static final String CODE_EXPORT_NOT_FOUND = "EXPORT_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_DUPLICATE_PURPOSE = "DUPLICATE_PURPOSE";

    private static final String OP_EXPORT = "EXPORT";

    private final ConsentRepository consentRepository;
    private final ExportRepository exportRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExportService(ConsentRepository consentRepository,
                         ExportRepository exportRepository,
                         IdempotencyRepository idempotencyRepository,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.consentRepository = consentRepository;
        this.exportRepository = exportRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成快照：任一用途当前无有效授权则整次 403 并指明该用途，不保存部分快照；
     * 主体在任何用途下都不存在返回 404。
     */
    @Transactional
    public ExportResponse export(ExportRequest request) {
        List<Purpose> purposes = normalizedPurposes(request);
        if (purposes.size() != request.purposes().size()) {
            throw ApiException.badRequest(CODE_DUPLICATE_PURPOSE, "用途集合不允许重复");
        }
        String fingerprint = OP_EXPORT + "|" + request.exportKey() + "|" + request.subjectKey()
                + "|" + purposes;
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody());
        }

        if (!consentRepository.existsAnyGrantForSubject(request.subjectKey())) {
            throw ApiException.notFound(CODE_SUBJECT_NOT_FOUND, "主体在任何用途下都不存在");
        }

        List<SnapshotPurposeView> purposeViews = new ArrayList<>();
        for (Purpose purpose : purposes) {
            // 行锁使生成与并发撤回按提交顺序裁决
            ConsentRepository.GrantRow latest = consentRepository
                    .findLatestGrantForUpdate(request.subjectKey(), purpose)
                    .filter(row -> row.status() == GrantStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.forbidden(CODE_PURPOSE_NOT_ACTIVE,
                            "用途 " + purpose + " 当前无有效授权"));
            List<ConsentRepository.RecordRow> records =
                    consentRepository.findRecords(request.subjectKey(), purpose, latest.epoch());
            purposeViews.add(new SnapshotPurposeView(purpose, latest.epoch(), records.size(),
                    SnapshotStatus.CURRENT,
                    records.stream().map(row -> new SnapshotRecordItem(row.recordKey(), row.payload())).toList()));
        }

        String generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock));
        try {
            exportRepository.insertSnapshot(request.exportKey(), request.subjectKey(), request.requestId(), generatedAt);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict(CODE_EXPORT_KEY_CONFLICT, "exportKey 已被占用");
        }
        for (SnapshotPurposeView view : purposeViews) {
            exportRepository.insertPurpose(request.exportKey(), view.purpose(), view.epoch(), view.recordCount());
            for (SnapshotRecordItem record : view.records()) {
                exportRepository.insertRecord(request.exportKey(), view.purpose(), view.epoch(),
                        record.recordKey(), record.payload());
            }
        }

        ExportResponse response = new ExportResponse(request.exportKey(), request.subjectKey(),
                generatedAt, purposeViews);
        storeSuccess(request.requestId(), fingerprint, response);
        return response;
    }

    /**
     * 按 exportKey 读取快照：返回固化内容并附带各用途当前状态；只读，不推进代次、不写入记录。
     */
    @Transactional(readOnly = true)
    public ExportResponse read(String exportKey) {
        ExportRepository.SnapshotRow snapshot = exportRepository.findSnapshot(exportKey)
                .orElseThrow(() -> ApiException.notFound(CODE_EXPORT_NOT_FOUND, "快照不存在"));
        List<SnapshotPurposeView> purposes = exportRepository.findPurposes(exportKey).stream()
                .map(purpose -> new SnapshotPurposeView(purpose.purpose(), purpose.epoch(),
                        purpose.recordCount(), currentStatus(snapshot.subjectKey(), purpose),
                        exportRepository.findRecords(exportKey, purpose.purpose()).stream()
                                .map(row -> new SnapshotRecordItem(row.recordKey(), row.payload()))
                                .toList()))
                .toList();
        return new ExportResponse(snapshot.exportKey(), snapshot.subjectKey(), snapshot.generatedAt(), purposes);
    }

    /**
     * 按主体列出全部快照汇总：只读，不推进代次、不写入记录。
     */
    @Transactional(readOnly = true)
    public ExportListResponse listBySubject(String subjectKey) {
        List<ExportListResponse.ExportSummaryView> snapshots = exportRepository.findSnapshotsBySubject(subjectKey)
                .stream()
                .map(snapshot -> new ExportListResponse.ExportSummaryView(snapshot.exportKey(), snapshot.generatedAt(),
                        exportRepository.findPurposes(snapshot.exportKey()).stream()
                                .map(purpose -> new ExportListResponse.PurposeSummary(purpose.purpose(),
                                        purpose.epoch(), purpose.recordCount(),
                                        currentStatus(subjectKey, purpose)))
                                .toList()))
                .toList();
        return new ExportListResponse(subjectKey, snapshots);
    }

    /**
     * 计算用途当前状态：当前有效代次与快照代次一致为 CURRENT，否则为 STALE。
     */
    private SnapshotStatus currentStatus(String subjectKey, ExportRepository.PurposeRow snapshotPurpose) {
        return consentRepository.findLatestGrant(subjectKey, snapshotPurpose.purpose())
                .filter(grant -> grant.status() == GrantStatus.ACTIVE && grant.epoch() == snapshotPurpose.epoch())
                .map(grant -> SnapshotStatus.CURRENT)
                .orElse(SnapshotStatus.STALE);
    }

    /**
     * 规范化用途集合：去重并按用途名字典序排序，使换序视为同参。
     */
    private List<Purpose> normalizedPurposes(ExportRequest request) {
        TreeSet<Purpose> sorted = new TreeSet<>(Comparator.comparing(Purpose::name));
        sorted.addAll(request.purposes());
        return new ArrayList<>(sorted);
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String fingerprint, ExportResponse response) {
        try {
            idempotencyRepository.insert(requestId, OP_EXPORT, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private String writeSnapshot(ExportResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private ExportResponse readSnapshot(String body) {
        try {
            return objectMapper.readValue(body, ExportResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
