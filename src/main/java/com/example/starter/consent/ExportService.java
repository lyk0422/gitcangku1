package com.example.starter.consent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ExportRepository.SnapshotPurposeRow;
import com.example.starter.consent.ExportRepository.SnapshotRecordRow;
import com.example.starter.consent.ExportRepository.SnapshotRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ExportListResponse;
import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 导出快照域服务：在一个事务内读取各用途当前有效代次及其全部记录，生成不可变快照。
 *
 * <p>一致性规则：生成时对授权代次行加行锁，与撤回的 UPDATE 互斥，
 * 二者按提交顺序裁决——生成先提交则快照完整保留（随后读取标记 STALE），
 * 撤回先提交则生成整次 403 且不保存任何内容。
 *
 * <p>幂等规则与授权域一致：成功结果与快照同事务保存；同一 requestId 相同参数
 * （用途集合换序视为同参）重放首次响应快照，参数变更返回 409；失败请求不占键。
 */
@Service
public class ExportService {

    static final String CODE_SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    static final String CODE_PURPOSE_NOT_ACTIVE = "PURPOSE_NOT_ACTIVE";
    static final String CODE_EXPORT_NOT_FOUND = "EXPORT_NOT_FOUND";
    static final String CODE_EXPORT_KEY_CONFLICT = "EXPORT_KEY_CONFLICT";

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
     * 生成导出快照：任一用途当前无有效授权则整次 403 并指明该用途；
     * 主体在任何用途下都不存在返回 404；exportKey 已被占用返回 409。
     */
    @Transactional
    public ExportResponse export(ExportRequest request) {
        List<Purpose> purposes = request.purposes().stream().sorted().toList();
        String fingerprint = OP_EXPORT + "|" + request.subjectKey() + "|" + request.exportKey() + "|"
                + purposes.stream().map(Purpose::name).collect(Collectors.joining(","));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody());
        }

        if (exportRepository.findSnapshot(request.exportKey()).isPresent()) {
            throw ApiException.conflict(CODE_EXPORT_KEY_CONFLICT, "exportKey 已被占用");
        }
        if (!consentRepository.existsAnyGrant(request.subjectKey())) {
            throw ApiException.notFound(CODE_SUBJECT_NOT_FOUND, "主体在任何用途下都不存在");
        }

        // 逐用途行锁读取当前有效代次，与撤回按提交顺序裁决
        Map<Purpose, ConsentRepository.GrantRow> activeGrants = new LinkedHashMap<>();
        for (Purpose purpose : purposes) {
            Optional<ConsentRepository.GrantRow> latest =
                    consentRepository.findLatestGrantForUpdate(request.subjectKey(), purpose);
            if (latest.isEmpty() || latest.get().status() != GrantStatus.ACTIVE) {
                throw ApiException.forbidden(CODE_PURPOSE_NOT_ACTIVE,
                        "用途 " + purpose.name() + " 当前无有效授权");
            }
            activeGrants.put(purpose, latest.get());
        }

        LocalDateTime generatedAt = LocalDateTime.now(clock);
        try {
            exportRepository.insertSnapshot(request.exportKey(), request.subjectKey(),
                    request.requestId(), generatedAt);
        } catch (DuplicateKeyException concurrent) {
            // 并发占用同一 exportKey：以已提交的快照为准
            throw ApiException.conflict(CODE_EXPORT_KEY_CONFLICT, "exportKey 已被占用");
        }

        List<ExportResponse.PurposeSnapshot> purposeSnapshots = new ArrayList<>();
        for (Purpose purpose : purposes) {
            int epoch = activeGrants.get(purpose).epoch();
            List<ConsentRepository.RecordRow> records =
                    consentRepository.findRecords(request.subjectKey(), purpose, epoch);
            exportRepository.insertPurpose(request.exportKey(), purpose, epoch, records.size());
            List<ExportResponse.SnapshotRecord> snapshotRecords = new ArrayList<>();
            for (int seq = 0; seq < records.size(); seq++) {
                ConsentRepository.RecordRow record = records.get(seq);
                exportRepository.insertRecord(request.exportKey(), purpose, seq,
                        record.recordKey(), record.payload());
                snapshotRecords.add(new ExportResponse.SnapshotRecord(record.recordKey(), record.payload()));
            }
            purposeSnapshots.add(new ExportResponse.PurposeSnapshot(
                    purpose, epoch, records.size(), SnapshotStatus.CURRENT, snapshotRecords));
        }

        ExportResponse response = new ExportResponse(
                request.exportKey(), request.subjectKey(), generatedAt, purposeSnapshots);
        storeSuccess(request.requestId(), fingerprint, response);
        return response;
    }

    /**
     * 按 exportKey 读取快照明细：返回固化内容并附带各用途当前状态；
     * 当前有效代次已偏离快照代次时标记 STALE，快照内容不变。
     */
    @Transactional(readOnly = true)
    public ExportResponse detail(String exportKey) {
        SnapshotRow snapshot = exportRepository.findSnapshot(exportKey)
                .orElseThrow(() -> ApiException.notFound(CODE_EXPORT_NOT_FOUND, "导出快照不存在"));
        List<ExportResponse.PurposeSnapshot> purposes = new ArrayList<>();
        for (SnapshotPurposeRow purposeRow : exportRepository.findPurposes(exportKey)) {
            List<ExportResponse.SnapshotRecord> records = new ArrayList<>();
            for (SnapshotRecordRow record : exportRepository.findRecords(exportKey, purposeRow.purpose())) {
                records.add(new ExportResponse.SnapshotRecord(record.recordKey(), record.payload()));
            }
            purposes.add(new ExportResponse.PurposeSnapshot(
                    purposeRow.purpose(), purposeRow.epoch(), purposeRow.recordCount(),
                    freshnessOf(snapshot.subjectKey(), purposeRow), records));
        }
        return new ExportResponse(snapshot.exportKey(), snapshot.subjectKey(), snapshot.createdAt(), purposes);
    }

    /**
     * 按主体列出全部快照摘要：只读，不推进代次、不写入记录。
     */
    @Transactional(readOnly = true)
    public ExportListResponse listBySubject(String subjectKey) {
        List<ExportListResponse.ExportSummary> summaries = new ArrayList<>();
        for (SnapshotRow snapshot : exportRepository.findSnapshotsBySubject(subjectKey)) {
            List<ExportListResponse.PurposeSummary> purposes = new ArrayList<>();
            for (SnapshotPurposeRow purposeRow : exportRepository.findPurposes(snapshot.exportKey())) {
                purposes.add(new ExportListResponse.PurposeSummary(
                        purposeRow.purpose(), purposeRow.epoch(), purposeRow.recordCount()));
            }
            summaries.add(new ExportListResponse.ExportSummary(
                    snapshot.exportKey(), snapshot.subjectKey(), snapshot.createdAt(), purposes));
        }
        return new ExportListResponse(subjectKey, summaries);
    }

    /**
     * 当前有效代次与快照代次一致为 CURRENT，否则（撤回或重新授权）为 STALE。
     */
    private SnapshotStatus freshnessOf(String subjectKey, SnapshotPurposeRow purposeRow) {
        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(subjectKey, purposeRow.purpose());
        boolean current = latest.isPresent()
                && latest.get().status() == GrantStatus.ACTIVE
                && latest.get().epoch() == purposeRow.epoch();
        return current ? SnapshotStatus.CURRENT : SnapshotStatus.STALE;
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

    private void storeSuccess(String requestId, String fingerprint, ExportResponse response) {
        try {
            idempotencyRepository.insert(requestId, OP_EXPORT, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
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
