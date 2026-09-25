package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.AttestationRepository.AttestationRow;
import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.QueryBatchRepository.BatchItemRow;
import com.example.starter.consent.QueryBatchRepository.BatchRow;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.ViolationDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批次查询域服务：创建批量数据查询时执行接收方证明门禁，成功后落库不可改写快照。
 *
 * <p>门禁规则：接收方被整体禁用则所有新查询 403；所有目标主体的当前授权代次均须存在
 * 该接收方未到期证明，任一缺失或已到期整次 403 并稳定列出主体与原因，不返回部分数据；
 * 证明精确绑定用途代次，用途拆分或迁移后旧代次证明不得复用。
 */
@Service
public class QueryBatchService {

    static final String CODE_RECIPIENT_DISABLED = "RECIPIENT_DISABLED";
    static final String CODE_ATTESTATION_GATE_FAILED = "ATTESTATION_GATE_FAILED";
    static final String CODE_BATCH_NOT_FOUND = "BATCH_NOT_FOUND";

    static final String REASON_NO_ACTIVE_GRANT = "NO_ACTIVE_GRANT";
    static final String REASON_ATTESTATION_MISSING = "ATTESTATION_MISSING";
    static final String REASON_ATTESTATION_EXPIRED = "ATTESTATION_EXPIRED";

    private final ConsentRepository consentRepository;
    private final AttestationRepository attestationRepository;
    private final QueryBatchRepository queryBatchRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QueryBatchService(ConsentRepository consentRepository,
                             AttestationRepository attestationRepository,
                             QueryBatchRepository queryBatchRepository,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.consentRepository = consentRepository;
        this.attestationRepository = attestationRepository;
        this.queryBatchRepository = queryBatchRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建批次查询：门禁全量校验通过后生成快照；任一主体不满足则整次 403，不落任何部分数据。
     */
    @Transactional
    public BatchQueryResponse create(BatchQueryRequest request) {
        if (attestationRepository.isRecipientDisabled(request.recipientId())) {
            throw ApiException.forbidden(CODE_RECIPIENT_DISABLED, "接收方已被整体禁用");
        }

        Instant now = clock.instant();
        List<String> subjects = new ArrayList<>(new TreeSet<>(request.subjectKeys()));
        List<ViolationDetail> violations = new ArrayList<>();
        record GatePass(String subjectKey, int epoch, int attestationVersion) {
        }
        List<GatePass> passed = new ArrayList<>();

        for (String subjectKey : subjects) {
            GrantRow latest = consentRepository.findLatestGrant(subjectKey, request.purpose()).orElse(null);
            if (latest == null || latest.status() != GrantStatus.ACTIVE) {
                violations.add(new ViolationDetail(subjectKey, REASON_NO_ACTIVE_GRANT));
                continue;
            }
            AttestationRow active = attestationRepository
                    .findActive(request.recipientId(), request.purpose(), latest.epoch())
                    .orElse(null);
            if (active == null) {
                violations.add(new ViolationDetail(subjectKey, REASON_ATTESTATION_MISSING));
            } else if (!active.expiresAt().isAfter(now)) {
                violations.add(new ViolationDetail(subjectKey, REASON_ATTESTATION_EXPIRED));
            } else {
                passed.add(new GatePass(subjectKey, latest.epoch(), active.version()));
            }
        }

        if (!violations.isEmpty()) {
            throw ApiException.forbidden(CODE_ATTESTATION_GATE_FAILED,
                    "部分主体缺少有效接收方证明，整次查询被拒绝", violations);
        }

        long batchId = queryBatchRepository.insertBatch(request.recipientId(), request.purpose());
        List<BatchQueryResponse.Item> items = new ArrayList<>();
        for (GatePass pass : passed) {
            List<BatchQueryResponse.RecordView> records = consentRepository
                    .findRecords(pass.subjectKey(), request.purpose(), pass.epoch())
                    .stream()
                    .map(row -> new BatchQueryResponse.RecordView(row.recordKey(), row.payload()))
                    .toList();
            queryBatchRepository.insertItem(batchId, pass.subjectKey(), pass.epoch(),
                    pass.attestationVersion(), writeRecords(records));
            items.add(new BatchQueryResponse.Item(pass.subjectKey(), pass.epoch(),
                    pass.attestationVersion(), records));
        }
        return new BatchQueryResponse(batchId, request.recipientId(), request.purpose(), items);
    }

    /**
     * 读取批次查询快照：快照及其授权代次、证明版本在创建后不可改写。
     */
    @Transactional(readOnly = true)
    public BatchQueryResponse get(long batchId) {
        BatchRow batch = queryBatchRepository.findBatch(batchId)
                .orElseThrow(() -> ApiException.notFound(CODE_BATCH_NOT_FOUND, "批次查询不存在"));
        List<BatchQueryResponse.Item> items = new ArrayList<>();
        for (BatchItemRow row : queryBatchRepository.findItems(batchId)) {
            items.add(new BatchQueryResponse.Item(row.subjectKey(), row.epoch(),
                    row.attestationVersion(), readRecords(row.recordsJson())));
        }
        return new BatchQueryResponse(batch.batchId(), batch.recipientId(), batch.purpose(), items);
    }

    private String writeRecords(List<BatchQueryResponse.RecordView> records) {
        try {
            return objectMapper.writeValueAsString(records);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记录快照序列化失败", e);
        }
    }

    private List<BatchQueryResponse.RecordView> readRecords(String recordsJson) {
        try {
            return objectMapper.readValue(recordsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记录快照反序列化失败", e);
        }
    }
}
