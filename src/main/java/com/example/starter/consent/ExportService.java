package com.example.starter.consent;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.ExportRepository.PurposeRow;
import com.example.starter.consent.ExportRepository.SnapshotRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;
import com.example.starter.consent.dto.ExportResponse.PurposeExport;
import com.example.starter.consent.dto.ExportResponse.SnapshotRecord;
import com.example.starter.consent.dto.ExportSummaryResponse;
import com.example.starter.consent.dto.ExportSummaryResponse.PurposeSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 导出快照域服务：在一个事务内读取各用途当前有效代次及其全部记录，生成不可变快照。
 *
 * <p>快照生成后不受后续写入、撤回与重新授权影响；按 exportKey 读取返回固化内容，
 * 并附带各用途当前状态（当前有效代次与快照不一致时标记 STALE）。
 *
 * <p>幂等规则与授权域一致：成功结果与快照同事务保存；同一 requestId 相同参数
 * （用途集合换序视为同参）重放返回首次响应快照，参数变更返回 409；失败请求不占键。
 *
 * <p>并发规则：生成时对授权代次行加锁读取（SELECT ... FOR UPDATE），与并发撤回
 * 按行锁串行化，由提交顺序裁决——生成先提交则快照完整保留（随后读取标记 STALE），
 * 撤回先提交则生成整次 403 且无内容。
 */
@Service
public class ExportService {

    static final String CODE_SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    static final String CODE_PURPOSE_NOT_GRANTED = "PURPOSE_NOT_GRANTED";
    static final String CODE_EXPORT_NOT_FOUND = "EXPORT_NOT_FOUND";
    static final String CODE_EXPORT_KEY_CONFLICT = "EXPORT_KEY_CONFLICT";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_EXPORT = "EXPORT";

    private final ConsentRepository consentRepository;
    private final ExportRepository exportRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ExportService(ConsentRepository consentRepository,
                         ExportRepository exportRepository,
                         IdempotencyRepository idempotencyRepository,
                         ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.exportRepository = exportRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成导出快照：任一用途当前无有效授权则整次 403 并指明该用途，不保存部分快照；
     * 主体在任何用途下都不存在返回 404。
     */
    @Transactional
    public ExportResponse create(ExportRequest request) {
        List<Purpose> purposes = normalizePurposes(request.purposes());
        String fingerprint = OP_EXPORT + "|" + request.exportKey() + "|" + request.subjectKey()
                + "|" + purposes.stream().map(Purpose::name).collect(Collectors.joining(","));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody());
        }

        if (!consentRepository.existsAnyGrant(request.subjectKey())) {
            throw ApiException.notFound(CODE_SUBJECT_NOT_FOUND, "主体不存在任何授权");
        }

        String joinedPurposes = purposes.stream().map(Purpose::name).collect(Collectors.joining(","));
        LocalDateTime createdAt = LocalDateTime.now();
        List<PurposeExport> purposeExports = purposes.stream().map(purpose -> {
            GrantRow active = lockActiveGrant(request.subjectKey(), purpose);
            List<RecordRow> records = consentRepository.findRecordsOfEpoch(
                    request.subjectKey(), purpose, active.epoch());
            return new PurposeExport(purpose, active.epoch(), records.size(),
                    ExportResponse.STATUS_CURRENT,
                    records.stream().map(r -> new SnapshotRecord(r.recordKey(), r.payload())).toList());
        }).toList();

        try {
            exportRepository.insertSnapshot(request.exportKey(), request.subjectKey(),
                    request.requestId(), joinedPurposes, createdAt);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict(CODE_EXPORT_KEY_CONFLICT, "exportKey 已存在");
        }
        for (PurposeExport purposeExport : purposeExports) {
            exportRepository.insertPurpose(request.exportKey(), purposeExport.purpose(),
                    purposeExport.epoch(), purposeExport.recordCount());
            for (SnapshotRecord record : purposeExport.records()) {
                exportRepository.insertRecord(request.exportKey(), purposeExport.purpose(),
                        record.recordKey(), record.payload());
            }
        }

        ExportResponse response = new ExportResponse(request.exportKey(), request.subjectKey(),
                createdAt, purposeExports);
        storeSuccess(request.requestId(), fingerprint, response);
        return response;
    }

    /**
     * 按 exportKey 读取快照明细：返回固化内容且重复读取稳定；附带各用途当前状态，
     * 当前有效代次已不是快照中的代次时标记 STALE，快照内容不变。只读，不推进代次、不写入记录。
     */
    @Transactional(readOnly = true)
    public ExportResponse read(String exportKey) {
        SnapshotRow snapshot = exportRepository.findSnapshot(exportKey)
                .orElseThrow(() -> ApiException.notFound(CODE_EXPORT_NOT_FOUND, "导出快照不存在"));
        List<PurposeExport> purposes = exportRepository.findPurposes(exportKey).stream()
                .map(purposeRow -> toPurposeExport(snapshot.subjectKey(), purposeRow))
                .toList();
        return new ExportResponse(snapshot.exportKey(), snapshot.subjectKey(), snapshot.createdAt(), purposes);
    }

    /**
     * 按主体查询快照列表：只读摘要，不含完整记录，不推进代次、不写入记录。
     */
    @Transactional(readOnly = true)
    public List<ExportSummaryResponse> listBySubject(String subjectKey) {
        return exportRepository.findSnapshotsBySubject(subjectKey).stream()
                .map(snapshot -> new ExportSummaryResponse(
                        snapshot.exportKey(), snapshot.subjectKey(), snapshot.createdAt(),
                        exportRepository.findPurposes(snapshot.exportKey()).stream()
                                .map(row -> new PurposeSummary(row.purpose(), row.epoch(), row.recordCount()))
                                .toList()))
                .toList();
    }

    /**
     * 规范化用途集合：去重并按字典序排序，使换序与重复项视为同参。
     */
    private List<Purpose> normalizePurposes(List<Purpose> purposes) {
        return purposes.stream().distinct()
                .sorted(Comparator.comparing(Purpose::name))
                .toList();
    }

    /**
     * 加锁读取当前有效代次：无授权或最新代次已撤回时整次 403 并指明该用途。
     */
    private GrantRow lockActiveGrant(String subjectKey, Purpose purpose) {
        return consentRepository.findGrantsForUpdate(subjectKey, purpose).stream()
                .max(Comparator.comparingInt(GrantRow::epoch))
                .filter(row -> row.status() == GrantStatus.ACTIVE)
                .orElseThrow(() -> ApiException.forbidden(CODE_PURPOSE_NOT_GRANTED,
                        "用途 " + purpose.name() + " 当前无有效授权"));
    }

    /**
     * 组装单用途快照内容，并附带当前状态：当前有效代次与快照不一致时标记 STALE。
     */
    private PurposeExport toPurposeExport(String subjectKey, PurposeRow purposeRow) {
        Optional<GrantRow> latest = consentRepository.findLatestGrant(subjectKey, purposeRow.purpose());
        boolean current = latest.isPresent()
                && latest.get().status() == GrantStatus.ACTIVE
                && latest.get().epoch() == purposeRow.epoch();
        String status = current ? ExportResponse.STATUS_CURRENT : ExportResponse.STATUS_STALE;
        List<SnapshotRecord> records = exportRepository
                .findRecords(purposeRow.exportKey(), purposeRow.purpose()).stream()
                .map(row -> new SnapshotRecord(row.recordKey(), row.payload()))
                .toList();
        return new PurposeExport(purposeRow.purpose(), purposeRow.epoch(),
                purposeRow.recordCount(), status, records);
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
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
