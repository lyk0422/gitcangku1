package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.AffectedEvidenceView;
import com.example.starter.evidence.dto.ContactBatchCreateRequest;
import com.example.starter.evidence.dto.ContactBatchView;
import com.example.starter.evidence.dto.ContaminationCreateRequest;
import com.example.starter.evidence.dto.ContaminationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 接触批次与污染传播服务。批次登记取号与污染传播扫描共用 contact_event_seq
 * 单行行锁作为串行边界：扩散提交后，后续新增接触必须在同一边界内重新核对
 * 污染状态，不会插入漏管批次。权限校验（当前保管人）先于幂等重放。
 */
@Service
public class ContactService {

    static final String OP_CONTACT_BATCH = "CONTACT_BATCH_REGISTER";
    static final String OP_CONTAMINATION = "CONTAMINATION_REGISTER";

    private final EvidenceRepository evidenceRepository;
    private final ContactBatchRepository contactBatchRepository;
    private final ContaminationRepository contaminationRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public ContactService(EvidenceRepository evidenceRepository,
                          ContactBatchRepository contactBatchRepository,
                          ContaminationRepository contaminationRepository,
                          CommandLogRepository commandLogRepository,
                          ObjectMapper objectMapper,
                          EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.contactBatchRepository = contactBatchRepository;
        this.contaminationRepository = contaminationRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记接触批次：当前保管人将同属自己的 2 至 5 件 SEALED 证物组成一次接触批次，
     * 时刻取服务器 UTC，保存全局递增事件序号。借出、待交接或已隔离对象不得参加。
     */
    @Transactional
    public StoredResponse registerBatch(String actorId, ContactBatchCreateRequest request,
                                        String requestHash) {
        List<String> members = request.evidenceKeys().stream().distinct().sorted().toList();
        if (members.size() != request.evidenceKeys().size()) {
            throw ApiException.badRequest("接触批次成员证物重复");
        }
        // 权限先于重放：全部成员须为操作人当前持有
        for (String key : members) {
            requireHeldBy(key, actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 串行边界：先取序列行锁，再按键序锁证物行，避免与交接/借出的持锁顺序成环
        long eventSeq = contactBatchRepository.nextEventSeq();
        LocalDateTime nowUtc = clock.nowUtc();
        for (String key : members) {
            Evidence locked = evidenceRepository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + key));
            requireCustodian(locked, actorId);
            if (locked.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict(
                        "成员证物状态不允许参加接触批次: " + key + " (" + locked.status() + ")");
            }
        }
        contactBatchRepository.insertBatch(request.requestId(), eventSeq, actorId,
                members.size(), nowUtc);
        for (String key : members) {
            contactBatchRepository.insertMember(request.requestId(), key, nowUtc);
        }
        ContactBatchView view = new ContactBatchView(request.requestId(), eventSeq, actorId,
                members, nowUtc);
        return record(request.requestId(), OP_CONTACT_BATCH, actorId, requestHash, 201, view);
    }

    /**
     * 登记污染：指定一件操作人当前持有的证物及一个已有事件序号作为污染起点。
     * 从起点序号（含）起按序号递增扫描接触批次：批次含已污染成员则全部成员加入
     * 污染集合，之前批次不追溯。整次原子标记受影响证物 CONTAMINATED（既有
     * SEAL_BROKEN 终态保留不覆盖），传播路径历史冻结，污染不可解除。
     */
    @Transactional
    public StoredResponse registerContamination(String actorId, ContaminationCreateRequest request,
                                                String requestHash) {
        // 权限先于重放：仅起点证物当前保管人可登记
        requireHeldBy(request.evidenceKey(), actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 串行边界：与批次登记共用序列行锁，扩散后不会插入漏管批次
        contactBatchRepository.lockSequence();
        Evidence lockedOrigin = evidenceRepository.findByKeyForUpdate(request.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.evidenceKey()));
        requireCustodian(lockedOrigin, actorId);
        if (!contactBatchRepository.existsBySeq(request.startSeq())) {
            throw ApiException.notFound("接触事件序号不存在: " + request.startSeq());
        }
        // 传播：evidenceKey -> 来源批次（起点为 null）
        Map<String, String> affected = new LinkedHashMap<>();
        affected.put(request.evidenceKey(), null);
        for (ContactBatch batch : contactBatchRepository.findBatchesFromSeq(request.startSeq())) {
            List<String> members = contactBatchRepository.findMembers(batch.batchKey());
            boolean hit = members.stream().anyMatch(affected::containsKey);
            if (hit) {
                for (String member : members) {
                    // 不能用 putIfAbsent：起点证物映射为 null，会被来源批次覆盖
                    if (!affected.containsKey(member)) {
                        affected.put(member, batch.batchKey());
                    }
                }
            }
        }
        LocalDateTime nowUtc = clock.nowUtc();
        for (String key : affected.keySet()) {
            evidenceRepository.markContaminated(key, nowUtc);
        }
        contaminationRepository.insert(request.requestId(), request.evidenceKey(),
                request.startSeq(), actorId, affected.size(), nowUtc);
        for (Map.Entry<String, String> entry : affected.entrySet()) {
            contaminationRepository.insertAffected(request.requestId(), entry.getKey(),
                    entry.getValue(), nowUtc);
        }
        return record(request.requestId(), OP_CONTAMINATION, actorId, requestHash, 201,
                toView(request.requestId(), request.evidenceKey(), request.startSeq(), actorId,
                        affected, nowUtc));
    }

    /**
     * 查询接触批次（含成员）。
     */
    @Transactional(readOnly = true)
    public ContactBatchView getBatch(String batchKey) {
        ContactBatch batch = contactBatchRepository.findBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("接触批次不存在: " + batchKey));
        List<String> members = contactBatchRepository.findMembers(batchKey);
        return new ContactBatchView(batch.batchKey(), batch.eventSeq(), batch.custodianId(),
                members, batch.createdAt());
    }

    /**
     * 查询污染登记（含冻结的传播路径）。
     */
    @Transactional(readOnly = true)
    public ContaminationView getContamination(String contaminationKey) {
        ContaminationRecord record = contaminationRepository.findByKey(contaminationKey)
                .orElseThrow(() -> ApiException.notFound("污染登记不存在: " + contaminationKey));
        Map<String, String> affected = new LinkedHashMap<>();
        for (ContaminationAffected row : contaminationRepository.findAffected(contaminationKey)) {
            affected.put(row.evidenceKey(), row.sourceBatchKey());
        }
        return toView(record.contaminationKey(), record.evidenceKey(), record.startSeq(),
                record.registeredBy(), affected, record.createdAt());
    }

    private void requireHeldBy(String evidenceKey, String actorId) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        requireCustodian(evidence, actorId);
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.forbidden("仅当前保管人可执行该操作: " + actorId);
        }
    }

    private ContaminationView toView(String contaminationKey, String evidenceKey, long startSeq,
                                     String registeredBy, Map<String, String> affected,
                                     LocalDateTime createdAt) {
        List<AffectedEvidenceView> views = new ArrayList<>();
        for (Map.Entry<String, String> entry : affected.entrySet()) {
            views.add(new AffectedEvidenceView(entry.getKey(), entry.getValue()));
        }
        return new ContaminationView(contaminationKey, evidenceKey, startSeq, registeredBy,
                views, createdAt);
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String requestId, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, requestHash, status, json,
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
}
