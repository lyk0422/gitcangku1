package com.example.starter.batch;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.batch.BatchRepository.BatchRow;
import com.example.starter.batch.BatchRepository.CommandRow;
import com.example.starter.batch.BatchRepository.TestResultRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批次隔离与放行核心业务。所有写操作在事务内先对批次行 SELECT ... FOR UPDATE 加锁，
 * 并发请求按事务提交顺序生效；幂等通过 command_log（命令级）与 test_result.test_key（检验级）保证。
 */
@Service
public class BatchService {

    static final String OP_CREATE = "CREATE_BATCH";
    static final String OP_TEST = "SUBMIT_TEST";
    static final String OP_APPROVE = "APPROVE";
    static final String OP_RECALL = "RECALL";

    private final BatchRepository repo;
    private final ObjectMapper objectMapper;

    public BatchService(BatchRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建批次。初始状态 QUARANTINED；batchKey 全局唯一；同 commandKey 同参重放返回首次结果。
     */
    @Transactional
    public BatchResponse createBatch(CreateBatchRequest req) {
        Set<String> distinct = new LinkedHashSet<>(req.requiredItems());
        if (distinct.size() != req.requiredItems().size()) {
            throw ApiException.badRequest("DUPLICATE_REQUIRED_ITEM", "必做检验项不得重复");
        }
        String fingerprint = fingerprint(req.batchKey(), req.productCode(), req.lotNumber(),
                String.valueOf(req.producedAt()), String.join(",", req.requiredItems()));
        BatchResponse replay = replayIfPresent(OP_CREATE, req.commandKey(), fingerprint, BatchResponse.class);
        if (replay != null) {
            return replay;
        }
        if (repo.findBatchByKey(req.batchKey()).isPresent()) {
            throw ApiException.conflict("BATCH_KEY_EXISTS", "batchKey 已存在: " + req.batchKey());
        }
        Instant now = now();
        long batchId;
        try {
            batchId = repo.insertBatch(req.batchKey(), req.productCode(), req.lotNumber(),
                    req.producedAt(), BatchStatus.QUARANTINED, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("BATCH_KEY_EXISTS", "batchKey 已存在: " + req.batchKey());
        }
        int seq = 0;
        for (String item : req.requiredItems()) {
            repo.insertRequiredItem(batchId, item, seq++);
        }
        BatchResponse response = new BatchResponse(req.batchKey(), req.productCode(), req.lotNumber(),
                req.producedAt(), BatchStatus.QUARANTINED, List.copyOf(req.requiredItems()));
        recordCommand(OP_CREATE, req.commandKey(), fingerprint, 201, response);
        return response;
    }

    /**
     * 提交检验结果。仅 QUARANTINED 批次可检验；任一 FAIL 立即 REJECTED；
     * 必做项全部 PASS 后进入 PENDING_RELEASE。testKey 同内容重放返回原结果，内容不同 409。
     */
    @Transactional
    public TestResultResponse submitTest(String batchKey, SubmitTestRequest req) {
        BatchRow batch = lockBatch(batchKey);
        String fingerprint = fingerprint(batchKey, req.testKey(), req.item(),
                req.outcome().name(), req.inspector());
        TestResultResponse replay = replayIfPresent(OP_TEST, req.commandKey(), fingerprint, TestResultResponse.class);
        if (replay != null) {
            return replay;
        }
        TestResultRow existing = repo.findTestResult(batch.id(), req.testKey()).orElse(null);
        if (existing != null) {
            boolean sameContent = existing.item().equals(req.item())
                    && existing.outcome() == req.outcome()
                    && existing.inspector().equals(req.inspector());
            if (!sameContent) {
                throw ApiException.conflict("TEST_KEY_CONFLICT",
                        "testKey 已存在且内容不同: " + req.testKey());
            }
            TestResultResponse response = toTestResponse(existing, batch.status());
            recordCommand(OP_TEST, req.commandKey(), fingerprint, 200, response);
            return response;
        }
        List<String> requiredItems = repo.findRequiredItems(batch.id());
        if (!requiredItems.contains(req.item())) {
            throw ApiException.notFound("TEST_ITEM_NOT_FOUND",
                    "检验项不属于批次必做项: " + req.item());
        }
        if (batch.status() != BatchStatus.QUARANTINED) {
            throw ApiException.conflict("BATCH_STATE_CONFLICT",
                    "当前状态 " + batch.status() + " 不允许提交检验");
        }
        Instant now = now();
        repo.insertTestResult(batch.id(), req.testKey(), req.item(), req.outcome(), req.inspector(), now);
        BatchStatus newStatus = batch.status();
        if (req.outcome() == TestOutcome.FAIL) {
            newStatus = BatchStatus.REJECTED;
        } else if (repo.countPassedItems(batch.id()) >= requiredItems.size()) {
            newStatus = BatchStatus.PENDING_RELEASE;
        }
        if (newStatus != batch.status()) {
            repo.updateBatchStatus(batch.id(), newStatus, now);
        }
        TestResultResponse response = new TestResultResponse(req.testKey(), req.item(), req.outcome(),
                req.inspector(), now, newStatus);
        recordCommand(OP_TEST, req.commandKey(), fingerprint, 200, response);
        return response;
    }

    /**
     * 放行批准。QUARANTINED（检验未满足）返回 422；REJECTED/RELEASED/RECALLED 返回 409；
     * 批准人不得是该批次任一检验人；每角色仅一次；两角色必须由不同人完成。
     * 首个有效批准进入 RELEASE_REVIEW，第二个不同角色批准后 RELEASED。
     */
    @Transactional
    public ApprovalResponse approve(String batchKey, String actorId, ApprovalRole role, String commandKey) {
        BatchRow batch = lockBatch(batchKey);
        String fingerprint = fingerprint(batchKey, actorId, role.name());
        ApprovalResponse replay = replayIfPresent(OP_APPROVE, commandKey, fingerprint, ApprovalResponse.class);
        if (replay != null) {
            return replay;
        }
        switch (batch.status()) {
            case QUARANTINED -> throw ApiException.unprocessable("TESTS_INCOMPLETE",
                    "必做检验项尚未全部 PASS，不能批准放行");
            case REJECTED, RELEASED, RECALLED -> throw ApiException.conflict("BATCH_STATE_CONFLICT",
                    "当前状态 " + batch.status() + " 不允许批准");
            default -> {
                // PENDING_RELEASE / RELEASE_REVIEW 可继续批准
            }
        }
        if (repo.existsTestResultByInspector(batch.id(), actorId)) {
            throw ApiException.conflict("APPROVER_IS_INSPECTOR",
                    "批准人不得是该批次任一检验结果的检验人: " + actorId);
        }
        if (repo.existsApprovalByRole(batch.id(), role)) {
            throw ApiException.conflict("ROLE_ALREADY_APPROVED", "角色已批准过该批次: " + role);
        }
        if (repo.existsApprovalByActor(batch.id(), actorId)) {
            throw ApiException.conflict("APPROVER_ALREADY_APPROVED",
                    "同一批准人不能完成两个角色的批准: " + actorId);
        }
        Instant now = now();
        repo.insertApproval(batch.id(), role, actorId, now);
        BatchStatus newStatus = batch.status() == BatchStatus.PENDING_RELEASE
                ? BatchStatus.RELEASE_REVIEW
                : BatchStatus.RELEASED;
        repo.updateBatchStatus(batch.id(), newStatus, now);
        ApprovalResponse response = new ApprovalResponse(role, actorId, now, newStatus);
        recordCommand(OP_APPROVE, commandKey, fingerprint, 200, response);
        return response;
    }

    /**
     * 召回。仅 RELEASED 批次可召回，召回后进入 RECALLED 并不再出现在当前可用批次查询中；
     * 检验、批准与召回历史保留。
     */
    @Transactional
    public RecallResponse recall(String batchKey, String actorId, RecallRequest req) {
        BatchRow batch = lockBatch(batchKey);
        String fingerprint = fingerprint(batchKey, actorId, req.reason());
        RecallResponse replay = replayIfPresent(OP_RECALL, req.commandKey(), fingerprint, RecallResponse.class);
        if (replay != null) {
            return replay;
        }
        if (batch.status() != BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_STATE_CONFLICT",
                    "仅已放行批次可召回，当前状态: " + batch.status());
        }
        Instant now = now();
        repo.insertRecall(batch.id(), req.reason(), actorId, now);
        repo.updateBatchStatus(batch.id(), BatchStatus.RECALLED, now);
        RecallResponse response = new RecallResponse(req.reason(), actorId, now, BatchStatus.RECALLED);
        recordCommand(OP_RECALL, req.commandKey(), fingerprint, 200, response);
        return response;
    }

    /** 当前可用批次：除 REJECTED 与 RECALLED 外的批次。 */
    @Transactional(readOnly = true)
    public List<BatchResponse> listAvailable() {
        return repo.findAvailableBatches().stream()
                .map(b -> new BatchResponse(b.batchKey(), b.productCode(), b.lotNumber(),
                        b.producedAt(), b.status(), repo.findRequiredItems(b.id())))
                .toList();
    }

    /** 批次完整历史明细：批次字段、必做项、检验、批准与召回记录。 */
    @Transactional(readOnly = true)
    public BatchDetailResponse detail(String batchKey) {
        BatchRow batch = repo.findBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchKey));
        List<String> items = repo.findRequiredItems(batch.id());
        List<TestResultResponse> tests = repo.findTestResults(batch.id()).stream()
                .map(t -> toTestResponse(t, batch.status()))
                .toList();
        List<ApprovalResponse> approvals = repo.findApprovals(batch.id()).stream()
                .map(a -> new ApprovalResponse(a.role(), a.actorId(), a.createdAt(), batch.status()))
                .toList();
        RecallResponse recall = repo.findRecall(batch.id())
                .map(r -> new RecallResponse(r.reason(), r.actorId(), r.createdAt(), batch.status()))
                .orElse(null);
        return new BatchDetailResponse(batch.batchKey(), batch.productCode(), batch.lotNumber(),
                batch.producedAt(), batch.status(), items, tests, approvals, recall);
    }

    /** 当前时间截断到微秒，与 DATETIME(6) 精度一致，保证重放响应与首次一致。 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private BatchRow lockBatch(String batchKey) {
        return repo.lockBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchKey));
    }

    private TestResultResponse toTestResponse(TestResultRow row, BatchStatus status) {
        return new TestResultResponse(row.testKey(), row.item(), row.outcome(), row.inspector(),
                row.createdAt(), status);
    }

    /** 长度前缀拼接，避免相邻字段拼接歧义。 */
    private static String fingerprint(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part.length()).append(':').append(part).append(';');
        }
        return sb.toString();
    }

    /**
     * 命令级幂等：同操作同键同参返回首次结果快照；同键改参抛 409。
     */
    private <T> T replayIfPresent(String operation, String commandKey, String fingerprint, Class<T> type) {
        CommandRow existing = repo.findCommand(operation, commandKey).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("COMMAND_KEY_CONFLICT",
                    "commandKey 已使用且请求参数不同: " + commandKey);
        }
        try {
            return objectMapper.readValue(existing.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("命令快照反序列化失败: " + commandKey, e);
        }
    }

    private void recordCommand(String operation, String commandKey, String fingerprint,
                               int responseStatus, Object response) {
        final String body;
        try {
            body = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("命令快照序列化失败: " + commandKey, e);
        }
        try {
            repo.insertCommand(operation, commandKey, fingerprint, responseStatus, body, now());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("COMMAND_KEY_CONFLICT",
                    "commandKey 并发冲突: " + commandKey);
        }
    }
}
