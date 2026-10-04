package com.example.starter.batch;

import com.example.starter.batch.dto.CleaningResponse;
import com.example.starter.batch.dto.ConfirmCleaningRequest;
import com.example.starter.batch.dto.LineSequenceResponse;
import com.example.starter.batch.dto.RegisterSequenceRequest;
import com.example.starter.batch.dto.SubmitCleaningRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生产线加工顺序与相邻批次清洗验证服务。
 *
 * <p>加工顺序：每条生产线顺序号从 1 连续递增，登记请求携带期望版本（期望顺序号），
 * 与生产线当前 next_seq 不一致返回 409，禁止插队；批次全局仅可登记一次，禁止一批挂多线。
 * 首批无需清洗；后续批次要求与前批产品代码相同，或两批之间存在有效清洗记录。
 *
 * <p>清洗验证：提交残留量为十进制字符串（0～100 毫克、最多 6 位小数）；
 * 另一名 QUALITY 角色确认且残留量（未舍入原值）不超过 0.1 毫克方为有效。
 * 残留超限或确认人同提交人返回 422，不生成有效清洗；清洗记录只增不改，
 * 第二次清洗只新增记录，旧不合格记录不可翻转为合格；确认明确引用 cleaningId。
 *
 * <p>并发策略：同一生产线上的登记与清洗提交对 production_line 行 SELECT ... FOR UPDATE
 * 串行化；清洗确认对 cleaning_record 行加锁；放行时冻结相邻批次与合格清洗记录。
 * 幂等经 {@link IdempotencyExecutor}：requestId + 结构化身份指纹，同键同参重放首次响应，
 * 异参 409，失败不占键，业务与去重原子提交。
 */
@Service
public class LineService {

    /**
     * 清洗有效判定阈值：残留量（未舍入）不超过 0.1 毫克。
     */
    static final BigDecimal MAX_VALID_RESIDUAL = new BigDecimal("0.1");

    private static final BigDecimal MAX_SUBMITTABLE = new BigDecimal("100");
    private static final Pattern RESIDUAL_PATTERN =
            Pattern.compile("^(0|[1-9]\\d{0,2})(\\.\\d{1,6})?$");

    private static final String CMD_REGISTER = "REGISTER_SEQUENCE";
    private static final String CMD_CLEANING_SUBMIT = "SUBMIT_CLEANING";
    private static final String CMD_CLEANING_CONFIRM = "CONFIRM_CLEANING";

    private final LineRepository lineRepo;
    private final BatchRepository batchRepo;
    private final IdempotencyExecutor idempotency;
    private final ObjectMapper objectMapper;

    public LineService(LineRepository lineRepo, BatchRepository batchRepo,
                       IdempotencyExecutor idempotency, ObjectMapper objectMapper) {
        this.lineRepo = lineRepo;
        this.batchRepo = batchRepo;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记批次上机：绑定一个未上机批次及其前一批次。首批无需清洗；后续批次要求
     * 与前批产品代码相同，或两批之间存在已确认且残留合格的清洗记录，否则 422。
     */
    public StoredResponse register(String lineId, String actorId, RegisterSequenceRequest req) {
        String operator = requireActor(actorId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("batchKey", req.batchKey());
        Map<String, Object> identity = identity("REGISTER_SEQUENCE", operator,
                "line:" + lineId, req.expectedVersion(), params);
        return idempotency.execute(CMD_REGISTER, req.requestId(), identity, () -> {
            LineRepository.LineRow line = lockLine(lineId);
            if (req.expectedVersion() != line.nextSeq()) {
                throw ApiException.conflict("顺序号不连续：期望版本 " + req.expectedVersion()
                        + "，当前下一顺序号 " + line.nextSeq());
            }
            BatchRepository.BatchRow batch = batchRepo.findBatch(req.batchKey())
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + req.batchKey()));
            lineRepo.findSequenceByBatch(req.batchKey()).ifPresent(existing -> {
                throw ApiException.conflict("批次已登记上机，禁止一批挂多线: " + req.batchKey());
            });
            List<LineRepository.LineSequenceRow> sequences = lineRepo.findSequences(lineId);
            String prevKey = sequences.isEmpty()
                    ? null
                    : sequences.get(sequences.size() - 1).batchKey();
            if (prevKey != null) {
                BatchRepository.BatchRow prev = batchRepo.findBatch(prevKey)
                        .orElseThrow(() -> ApiException.notFound("前一批次不存在: " + prevKey));
                if (!prev.productCode().equals(batch.productCode())
                        && !hasValidCleaning(lineId, prevKey, req.batchKey())) {
                    throw ApiException.unprocessable("前后批产品代码不同且两批之间不存在有效清洗记录: "
                            + prevKey + " -> " + req.batchKey());
                }
            }
            String now = now();
            LineRepository.LineSequenceRow row = new LineRepository.LineSequenceRow(0L, lineId,
                    line.nextSeq(), req.batchKey(), prevKey, req.requestId(), operator, now);
            lineRepo.insertSequence(row);
            lineRepo.updateNextSeq(lineId, line.nextSeq() + 1);
            LineSequenceResponse body = new LineSequenceResponse(lineId, line.nextSeq(),
                    req.batchKey(), prevKey, operator, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }
    /**
     * 提交清洗记录：残留量为十进制字符串（0～100 毫克、最多 6 位小数，判定用未舍入原值）。
     * 初始 PENDING；expectedVersion 为该（线, 前批, 后批）组合已有清洗记录数。
     */
    public StoredResponse submitCleaning(String lineId, String actorId, SubmitCleaningRequest req) {
        String operator = requireActor(actorId);
        parseResidual(req.residualAmount());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cleaningId", req.cleaningId());
        params.put("fromBatchKey", req.fromBatchKey());
        params.put("toBatchKey", req.toBatchKey());
        params.put("residualAmount", req.residualAmount());
        Map<String, Object> identity = identity("SUBMIT_CLEANING", operator,
                "line:" + lineId, req.expectedVersion(), params);
        return idempotency.execute(CMD_CLEANING_SUBMIT, req.requestId(), identity, () -> {
            if (req.fromBatchKey().equals(req.toBatchKey())) {
                throw ApiException.badRequest("前批与后批不能相同: " + req.fromBatchKey());
            }
            batchRepo.findBatch(req.fromBatchKey())
                    .orElseThrow(() -> ApiException.notFound("前批不存在: " + req.fromBatchKey()));
            batchRepo.findBatch(req.toBatchKey())
                    .orElseThrow(() -> ApiException.notFound("后批不存在: " + req.toBatchKey()));
            lockLine(lineId);
            int count = lineRepo.countCleanings(lineId, req.fromBatchKey(), req.toBatchKey());
            if (req.expectedVersion() != count) {
                throw ApiException.conflict("清洗记录版本不符：期望版本 " + req.expectedVersion()
                        + "，当前记录数 " + count);
            }
            lineRepo.findCleaning(req.cleaningId()).ifPresent(existing -> {
                throw ApiException.conflict("cleaningId 已存在: " + req.cleaningId());
            });
            String now = now();
            LineRepository.CleaningRow row = new LineRepository.CleaningRow(0L, req.cleaningId(),
                    lineId, req.fromBatchKey(), req.toBatchKey(), req.residualAmount(), "PENDING",
                    operator, now, null, null, 0);
            lineRepo.insertCleaning(row);
            return new StoredResponse(201, toJson(toCleaningResponse(row)));
        });
    }

    /**
     * 确认清洗：确认人须为 QUALITY 角色且不同于提交人，残留量（未舍入原值）不超过
     * 0.1 毫克；残留超限或确认人同提交人返回 422，不生成有效清洗。
     * 确认明确引用 cleaningId，记录只增不改，旧不合格记录不可翻转为合格。
     */
    public StoredResponse confirm(String cleaningId, String actorId, String roleHeader,
                                  ConfirmCleaningRequest req) {
        String operator = requireActor(actorId);
        ApprovalRole role = parseRole(roleHeader);
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.unprocessable("清洗确认人必须为 QUALITY 角色");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("role", role.name());
        Map<String, Object> identity = identity("CONFIRM_CLEANING", operator,
                "cleaning:" + cleaningId, req.expectedVersion(), params);
        return idempotency.execute(CMD_CLEANING_CONFIRM, req.requestId(), identity, () -> {
            LineRepository.CleaningRow row = lineRepo.findCleaningForUpdate(cleaningId)
                    .orElseThrow(() -> ApiException.notFound("清洗记录不存在: " + cleaningId));
            if (req.expectedVersion() != row.version()) {
                throw ApiException.conflict("清洗记录版本不符：期望版本 " + req.expectedVersion()
                        + "，当前版本 " + row.version());
            }
            if (!"PENDING".equals(row.status())) {
                throw ApiException.conflict("清洗记录已确认，不能重复确认: " + cleaningId);
            }
            if (row.submittedBy().equals(operator)) {
                throw ApiException.unprocessable("确认人不得与提交人相同: " + operator);
            }
            BigDecimal residual = new BigDecimal(row.residualAmount());
            if (residual.compareTo(MAX_VALID_RESIDUAL) > 0) {
                throw ApiException.unprocessable("残留量 " + row.residualAmount()
                        + " 毫克超过 0.1 毫克上限，清洗不合格");
            }
            String now = now();
            lineRepo.confirmCleaning(cleaningId, operator, now);
            LineRepository.CleaningRow confirmed = new LineRepository.CleaningRow(row.id(),
                    row.cleaningId(), row.lineId(), row.fromBatchKey(), row.toBatchKey(),
                    row.residualAmount(), "CONFIRMED", row.submittedBy(), row.submittedAt(),
                    operator, now, 1);
            return new StoredResponse(201, toJson(toCleaningResponse(confirmed)));
        });
    }
    /**
     * 放行守卫（在批次批准事务内调用）：批次已上机登记且非首批时，
     * 冻结相邻批次与合格清洗记录——锁定生产线行与全部前批批次行，
     * 任一前批处于召回血缘中（自身被召回或任一祖先被召回）返回 422；
     * 与前批产品代码不同时，须存在针对该实际前批与本批的已确认且残留合格清洗，
     * 不得沿用上一对批次的清洗。未登记批次与首批无附加约束。
     */
    public void assertReleasable(String batchKey) {
        Optional<LineRepository.LineSequenceRow> registration = lineRepo.findSequenceByBatch(batchKey);
        if (registration.isEmpty() || registration.get().seq() == 1) {
            return;
        }
        LineRepository.LineSequenceRow reg = registration.get();
        lineRepo.findLineForUpdate(reg.lineId());
        List<LineRepository.LineSequenceRow> predecessors =
                lineRepo.findPredecessors(reg.lineId(), reg.seq());
        // 冻结相邻批次：按业务键排序逐行锁定，与召回/检验/批准互斥，按事务提交顺序裁决
        predecessors.stream().map(LineRepository.LineSequenceRow::batchKey).sorted()
                .forEach(batchRepo::findBatchForUpdate);
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow edge : batchRepo.findAllLineage()) {
            parentOf.put(edge.childKey(), edge.parentKey());
        }
        for (LineRepository.LineSequenceRow predecessor : predecessors) {
            if (recalled.contains(predecessor.batchKey())
                    || recalledAncestor(predecessor.batchKey(), parentOf, recalled).isPresent()) {
                throw ApiException.unprocessable(
                        "前批 " + predecessor.batchKey() + " 处于召回血缘中，不得放行");
            }
        }
        BatchRepository.BatchRow current = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        String prevKey = predecessors.get(predecessors.size() - 1).batchKey();
        BatchRepository.BatchRow prev = batchRepo.findBatch(prevKey)
                .orElseThrow(() -> ApiException.notFound("前一批次不存在: " + prevKey));
        if (!prev.productCode().equals(current.productCode())
                && !hasValidCleaning(reg.lineId(), prevKey, batchKey)) {
            throw ApiException.unprocessable("前后批产品代码不同且两批之间不存在有效清洗记录，不得放行: "
                    + prevKey + " -> " + batchKey);
        }
    }

    /**
     * 生产线加工顺序查询：按顺序号升序；生产线不存在返回 404。
     */
    public List<LineSequenceResponse> listSequences(String lineId) {
        lineRepo.findLine(lineId)
                .orElseThrow(() -> ApiException.notFound("生产线不存在: " + lineId));
        return lineRepo.findSequences(lineId).stream()
                .map(row -> new LineSequenceResponse(row.lineId(), row.seq(), row.batchKey(),
                        row.prevBatchKey(), row.operatorId(), Instant.parse(row.createdAt())))
                .toList();
    }

    /**
     * 清洗记录查询；不存在返回 404。
     */
    public CleaningResponse getCleaning(String cleaningId) {
        return lineRepo.findCleaning(cleaningId)
                .map(this::toCleaningResponse)
                .orElseThrow(() -> ApiException.notFound("清洗记录不存在: " + cleaningId));
    }

    /**
     * 锁定生产线行（不存在则建立），串行化该线上的登记与清洗提交。
     */
    private LineRepository.LineRow lockLine(String lineId) {
        if (lineRepo.findLine(lineId).isEmpty()) {
            try {
                lineRepo.insertLine(lineId, 1, now());
            } catch (DuplicateKeyException e) {
                // 并发建线：对方已提交，重查即可
            }
        }
        return lineRepo.findLineForUpdate(lineId)
                .orElseThrow(() -> ApiException.notFound("生产线不存在: " + lineId));
    }

    /**
     * 是否存在已确认且残留量（未舍入原值）不超过 0.1 毫克的清洗记录。
     */
    private boolean hasValidCleaning(String lineId, String fromBatchKey, String toBatchKey) {
        return lineRepo.findConfirmedCleaningsForUpdate(lineId, fromBatchKey, toBatchKey).stream()
                .anyMatch(c -> new BigDecimal(c.residualAmount()).compareTo(MAX_VALID_RESIDUAL) <= 0);
    }

    /**
     * 校验残留量十进制字符串：0～100 毫克、最多 6 位小数；非法返回 400。
     */
    private BigDecimal parseResidual(String raw) {
        String value = raw.trim();
        if (!RESIDUAL_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest(
                    "residualAmount 必须为 0~100 毫克、最多 6 位小数的十进制字符串: " + raw);
        }
        BigDecimal amount = new BigDecimal(value);
        if (amount.compareTo(MAX_SUBMITTABLE) > 0) {
            throw ApiException.badRequest("residualAmount 超出 0~100 毫克范围: " + raw);
        }
        return amount;
    }

    /**
     * 沿拆分父链向上查找最近的被直接召回（RECALLED）祖先；批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, String> parentOf,
                                              Set<String> recalled) {
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private CleaningResponse toCleaningResponse(LineRepository.CleaningRow row) {
        return new CleaningResponse(row.cleaningId(), row.lineId(), row.fromBatchKey(),
                row.toBatchKey(), row.residualAmount(), row.status(), row.submittedBy(),
                Instant.parse(row.submittedAt()), row.confirmedBy(),
                row.confirmedAt() == null ? null : Instant.parse(row.confirmedAt()), row.version());
    }

    private Map<String, Object> identity(String operation, String operator, String resource,
                                         Long expectedVersion, Map<String, Object> params) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", operation);
        identity.put("operator", operator);
        identity.put("resource", resource);
        identity.put("expectedVersion", expectedVersion);
        identity.put("params", params);
        return identity;
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private ApprovalRole parseRole(String roleHeader) {
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        try {
            return ApprovalRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Approval-Role 必须为 QUALITY 或 OPERATIONS");
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }
}