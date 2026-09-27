package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.SnapshotRepository.SnapshotRow;
import com.example.starter.consent.dto.CrossQueryRequest;
import com.example.starter.consent.dto.CrossQueryResponse;
import com.example.starter.consent.dto.CrossQueryResponse.QueryItem;
import com.example.starter.consent.dto.SnapshotSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跨域查询服务：一次跨对象查询指定允许区域集合，仅在授权有效、epoch 一致且全部对象
 * 驻留区域均在集合内时返回数据；任一对象不满足则整次 403/410，不返回部分结果。
 *
 * <p>成功时生成冻结的合规快照：固化每个对象的 epoch、驻留区域与授权版本；
 * 后续迁移、撤回或清除均不改写已生成的快照。requestId 同参重放返回首次完整快照，
 * 异参 409，失败不占键。
 */
@Service
public class QueryService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_DATA_ERASED = "DATA_ERASED";
    static final String CODE_REGION_NOT_ALLOWED = "REGION_NOT_ALLOWED";

    private static final String OP_QUERY = "QUERY";

    private final ConsentRepository consentRepository;
    private final SnapshotRepository snapshotRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QueryService(ConsentRepository consentRepository,
                        SnapshotRepository snapshotRepository,
                        IdempotencyRepository idempotencyRepository,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.consentRepository = consentRepository;
        this.snapshotRepository = snapshotRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 跨域查询：全部对象校验通过后生成冻结快照并返回；任一对象不满足则整次失败。
     */
    @Transactional
    public CrossQueryResponse query(CrossQueryRequest request) {
        List<String> allowedRegions = new ArrayList<>(new TreeSet<>(request.allowedRegions()));
        String fingerprint = OP_QUERY + "|" + String.join(",", allowedRegions) + "|" + objectsFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), CrossQueryResponse.class);
        }

        // 先校验全部对象的完整最终状态，任一失败整次拒绝，不产生快照
        List<QueryItem> items = new ArrayList<>();
        for (CrossQueryRequest.ObjectRef ref : request.objects()) {
            items.add(resolveItem(ref, allowedRegions));
        }

        // 全部通过后在一个事务中固化快照
        String snapshotId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now(clock);
        snapshotRepository.insertSnapshot(snapshotId, request.requestId(),
                String.join(",", allowedRegions), request.objects().size(), items.size(), createdAt);
        for (int seq = 0; seq < items.size(); seq++) {
            snapshotRepository.insertItem(snapshotId, seq, items.get(seq));
        }

        CrossQueryResponse response = new CrossQueryResponse(snapshotId, createdAt,
                request.objects().size(), items.size(), List.copyOf(items));
        storeSuccess(request.requestId(), OP_QUERY, fingerprint, response);
        return response;
    }

    /**
     * 快照明细：读取冻结快照，后续迁移、撤回、清除不影响已生成快照。只读。
     */
    @Transactional(readOnly = true)
    public CrossQueryResponse snapshotDetail(String snapshotId) {
        SnapshotRow snapshot = snapshotRepository.findSnapshot(snapshotId)
                .orElseThrow(() -> ApiException.notFound(CODE_SNAPSHOT_NOT_FOUND, "查询快照不存在: " + snapshotId));
        List<QueryItem> items = snapshotRepository.findItems(snapshotId);
        return new CrossQueryResponse(snapshot.snapshotId(), snapshot.createdAt(),
                snapshot.requestedCount(), snapshot.itemCount(), items);
    }

    /**
     * 快照历史：按生成时间（UTC）稳定排序；from/to 均为 UTC，区间左闭右开 [from, to)。只读。
     */
    @Transactional(readOnly = true)
    public List<SnapshotSummary> snapshotHistory(Instant from, Instant to) {
        return snapshotRepository.findHistory(from, to);
    }

    /**
     * 解析单个对象：授权有效、epoch 一致、未清除、区域在允许集合内。
     */
    private QueryItem resolveItem(CrossQueryRequest.ObjectRef ref, List<String> allowedRegions) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(ref.subjectKey(), ref.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND,
                        "授权不存在: " + ref.subjectKey() + "/" + ref.purpose()));

        ConsentRepository.GrantRow grant;
        if (ref.epoch() != null) {
            // 指定 epoch：必须与当前最新代次一致；旧代次（已撤回/被迁移取代）整次 410
            grant = consentRepository.findGrant(ref.subjectKey(), ref.purpose(), ref.epoch())
                    .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND,
                            "授权代次不存在: epoch=" + ref.epoch()));
            if (grant.epoch() != latest.epoch() || grant.status() == GrantStatus.REVOKED) {
                throw ApiException.gone(CODE_CONSENT_REVOKED,
                        "授权代次已撤回或被迁移取代: epoch=" + ref.epoch());
            }
        } else {
            grant = latest;
            if (grant.status() == GrantStatus.REVOKED) {
                throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
            }
        }

        ConsentRepository.RecordRow record = consentRepository
                .findRecord(ref.subjectKey(), ref.purpose(), grant.epoch(), ref.recordKey())
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND,
                        "记录不存在: " + ref.recordKey()));
        if (record.erasedAt() != null) {
            throw ApiException.gone(CODE_DATA_ERASED, "记录已清除: " + ref.recordKey());
        }
        if (record.regionCode() == null || !allowedRegions.contains(record.regionCode())) {
            throw ApiException.forbidden(CODE_REGION_NOT_ALLOWED,
                    "对象驻留区域不在允许集合内: " + ref.recordKey() + " 区域=" + record.regionCode());
        }
        return new QueryItem(record.subjectKey(), record.purpose(), record.epoch(),
                grant.consentVersion(), record.regionCode(), record.recordKey(), record.payload());
    }

    private String objectsFingerprint(CrossQueryRequest request) {
        StringBuilder builder = new StringBuilder();
        for (CrossQueryRequest.ObjectRef ref : request.objects()) {
            builder.append(ref.subjectKey()).append('|').append(ref.purpose()).append('|')
                    .append(ref.recordKey()).append('|')
                    .append(ref.epoch() == null ? "" : ref.epoch().toString()).append(';');
        }
        return builder.toString();
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

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
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
