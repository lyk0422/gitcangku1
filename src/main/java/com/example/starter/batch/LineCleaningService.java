package com.example.starter.batch;

import com.example.starter.batch.dto.CleaningConfirmRequest;
import com.example.starter.batch.dto.CleaningResponse;
import com.example.starter.batch.dto.CleaningSubmitRequest;
import com.example.starter.batch.dto.LineRunRequest;
import com.example.starter.batch.dto.LineRunResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产线加工顺序与批间清洗验证服务。
 *
 * <p>加工顺序：每条生产线 seq 从 1 连续递增，登记绑定一个未上机批次及同线前一批次；
 * 首批无需清洗，后续批次要求前后产品代码相同或已存在两批之间的清洗记录。
 *
 * <p>清洗：提交残留量十进制字符串（0~100 毫克、最多 6 位小数，按原文存储不舍入）；
 * 另一名 QUALITY 确认且残留（未舍入）不超过 0.1 毫克才有效。残留超限的确认使记录
 * 进入 REJECTED 终态并返回 422；再次清洗只能新增记录，旧记录不改写。
 *
 * <p>幂等：requestId 为幂等键，身份含操作、操作者、资源、全部参数与期望版本，
 * 结构化序列化后取摘要，避免分隔符碰撞；同键同参重放首次响应，异参 409，失败不占键。
 */
@Service
public class LineCleaningService {

    private static final String CMD_LINE_RUN = "LINE_RUN";
    private static final String CMD_CLEANING_SUBMIT = "CLEANING_SUBMIT";
    private static final String CMD_CLEANING_CONFIRM = "CLEANING_CONFIRM";

    /**
     * 放行阈值：残留量（未舍入）不超过 0.1 毫克才有效。
     */
    static final BigDecimal RELEASE_THRESHOLD = new BigDecimal("0.1");

    /**
     * 残留量参数上限：100 毫克（含）。
     */
    private static final BigDecimal RESIDUAL_MAX = new BigDecimal("100");

    private final BatchRepository repo;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public LineCleaningService(BatchRepository repo, IdempotentExecutor idempotent,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 上机登记：顺序号等于同线当前最大顺序加一（首批为 1），否则视为插队返回 409；
     * 一批仅可上机一次；后续批次产品不同须已存在两批之间的清洗记录。
     */
    public StoredResponse registerRun(String lineCode, String actorId, LineRunRequest req) {
        String operator = requireActor(actorId);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", CMD_LINE_RUN);
        identity.put("operator", operator);
        identity.put("lineCode", lineCode);
        identity.put("batchKey", req.batchKey());
        identity.put("expectedSeq", req.expectedSeq());
        String fingerprint = idempotent.structuredFingerprint(identity);
        return idempotent.execute(CMD_LINE_RUN, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatch(req.batchKey())
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + req.batchKey()));
            repo.findLineRunByBatch(req.batchKey()).ifPresent(r -> {
                throw ApiException.conflict("批次已上机，不得重复登记或挂多条线: " + req.batchKey());
            });
            var last = repo.findLastLineRunForUpdate(lineCode);
            int nextSeq = last.map(r -> r.seq() + 1).orElse(1);
            if (req.expectedSeq() != nextSeq) {
                throw ApiException.conflict(
                        "加工顺序须从 1 连续递增，禁止插队：期望 " + nextSeq + "，实收 " + req.expectedSeq());
            }
            String prevKey = last.map(BatchRepository.LineRunRow::batchKey).orElse(null);
            String prevProduct = null;
            boolean cleaningRequired = false;
            if (prevKey != null) {
                BatchRepository.BatchRow prev = repo.findBatch(prevKey)
                        .orElseThrow(() -> ApiException.notFound("前一批次不存在: " + prevKey));
                prevProduct = prev.productCode();
                cleaningRequired = !prev.productCode().equals(batch.productCode());
                if (cleaningRequired
                        && repo.findCleaningsBetween(prevKey, req.batchKey()).isEmpty()) {
                    throw ApiException.unprocessable(
                            "前后产品代码不同，须先提交两批之间的清洗记录: " + prevKey + " -> " + req.batchKey());
                }
            }
            String now = now();
            repo.insertLineRun(new BatchRepository.LineRunRow(0L, lineCode, nextSeq,
                    req.batchKey(), prevKey, now));
            LineRunResponse body = new LineRunResponse(lineCode, nextSeq, req.batchKey(), prevKey,
                    batch.productCode(), prevProduct, cleaningRequired, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 提交清洗：残留量按原文存储（未舍入），初始 PENDING；两批必须均已存在。
     */
    public StoredResponse submitCleaning(String actorId, CleaningSubmitRequest req) {
        String operator = requireActor(actorId);
        parseResidual(req.residual());
        if (req.prevBatchKey().equals(req.nextBatchKey())) {
            throw ApiException.badRequest("前后批次不得相同: " + req.prevBatchKey());
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", CMD_CLEANING_SUBMIT);
        identity.put("operator", operator);
        identity.put("prevBatchKey", req.prevBatchKey());
        identity.put("nextBatchKey", req.nextBatchKey());
        identity.put("residual", req.residual());
        String fingerprint = idempotent.structuredFingerprint(identity);
        return idempotent.execute(CMD_CLEANING_SUBMIT, req.requestId(), fingerprint, () -> {
            repo.findBatch(req.prevBatchKey())
                    .orElseThrow(() -> ApiException.notFound("前批不存在: " + req.prevBatchKey()));
            repo.findBatch(req.nextBatchKey())
                    .orElseThrow(() -> ApiException.notFound("后批不存在: " + req.nextBatchKey()));
            String now = now();
            repo.insertCleaning(new BatchRepository.CleaningRow(0L, req.prevBatchKey(),
                    req.nextBatchKey(), req.residual(), operator, CleaningStatus.PENDING.name(),
                    0, null, now, null));
            long id = repo.findCleaningId(req.prevBatchKey(), req.nextBatchKey(), req.residual(),
                    operator, now);
            CleaningResponse body = new CleaningResponse(id, req.prevBatchKey(), req.nextBatchKey(),
                    req.residual(), CleaningStatus.PENDING.name(), 0, operator, null,
                    Instant.parse(now), null);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 确认清洗：确认人须为 QUALITY 且不同于提交人；期望版本须匹配当前版本。
     * 残留（未舍入）超过 0.1 毫克时记录进入 REJECTED 终态并返回 422，不生成有效清洗；
     * 该失败响应不占幂等键，但不合格记录随事务保留，不可被后续确认改写为合格。
     */
    public StoredResponse confirmCleaning(long cleaningId, String actorId, String roleHeader,
                                          CleaningConfirmRequest req) {
        String operator = requireActor(actorId);
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        final ApprovalRole role;
        try {
            role = ApprovalRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Approval-Role 必须为 QUALITY 或 OPERATIONS");
        }
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.unprocessable("清洗确认必须由 QUALITY 角色完成");
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", CMD_CLEANING_CONFIRM);
        identity.put("operator", operator);
        identity.put("cleaningId", cleaningId);
        identity.put("expectedVersion", req.expectedVersion());
        String fingerprint = idempotent.structuredFingerprint(identity);
        return idempotent.execute(CMD_CLEANING_CONFIRM, req.requestId(), fingerprint, () -> {
            BatchRepository.CleaningRow rec = repo.findCleaningForUpdate(cleaningId)
                    .orElseThrow(() -> ApiException.notFound("清洗记录不存在: " + cleaningId));
            if (!CleaningStatus.PENDING.name().equals(rec.status())) {
                throw ApiException.conflict("清洗记录已终结（" + rec.status() + "），不可再次确认");
            }
            if (req.expectedVersion() != rec.version()) {
                throw ApiException.conflict("期望版本不匹配：当前 " + rec.version()
                        + "，实收 " + req.expectedVersion());
            }
            if (rec.submittedBy().equals(operator)) {
                throw ApiException.unprocessable("确认人不得与提交人为同一人: " + operator);
            }
            String now = now();
            BigDecimal residual = new BigDecimal(rec.residual());
            if (residual.compareTo(RELEASE_THRESHOLD) > 0) {
                // 残留超限（未舍入判断）：记录判为 REJECTED 终态，返回 422，失败不占幂等键
                repo.transitionCleaning(cleaningId, rec.version(), CleaningStatus.REJECTED.name(),
                        operator, now);
                CleaningResponse body = toCleaningResponse(repo.findCleaning(cleaningId).orElseThrow());
                return new StoredResponse(422, toJson(body));
            }
            repo.transitionCleaning(cleaningId, rec.version(), CleaningStatus.CONFIRMED.name(),
                    operator, now);
            CleaningResponse body = toCleaningResponse(repo.findCleaning(cleaningId).orElseThrow());
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 某生产线的全部加工顺序登记，按顺序号升序。
     */
    public List<LineRunResponse> listRuns(String lineCode) {
        return repo.findLineRuns(lineCode).stream()
                .map(r -> {
                    BatchRepository.BatchRow batch = repo.findBatch(r.batchKey()).orElseThrow();
                    String prevProduct = r.prevBatchKey() == null ? null
                            : repo.findBatch(r.prevBatchKey()).orElseThrow().productCode();
                    boolean cleaningRequired = r.prevBatchKey() != null
                            && !batch.productCode().equals(prevProduct);
                    return new LineRunResponse(r.lineCode(), r.seq(), r.batchKey(), r.prevBatchKey(),
                            batch.productCode(), prevProduct, cleaningRequired,
                            Instant.parse(r.createdAt()));
                })
                .toList();
    }

    /**
     * 查询单条清洗记录。
     */
    public CleaningResponse getCleaning(long cleaningId) {
        return toCleaningResponse(repo.findCleaning(cleaningId)
                .orElseThrow(() -> ApiException.notFound("清洗记录不存在: " + cleaningId)));
    }

    /**
     * 解析残留量：十进制字符串，范围 0~100 毫克；格式或范围非法返回 400。
     */
    private BigDecimal parseResidual(String residual) {
        final BigDecimal value;
        try {
            value = new BigDecimal(residual);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("residual 必须为十进制字符串: " + residual);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(RESIDUAL_MAX) > 0) {
            throw ApiException.badRequest("residual 须在 0~100 毫克范围内: " + residual);
        }
        return value;
    }

    private CleaningResponse toCleaningResponse(BatchRepository.CleaningRow row) {
        return new CleaningResponse(row.id(), row.prevBatchKey(), row.nextBatchKey(), row.residual(),
                row.status(), row.version(), row.submittedBy(), row.confirmedBy(),
                Instant.parse(row.createdAt()),
                row.confirmedAt() == null ? null : Instant.parse(row.confirmedAt()));
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
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