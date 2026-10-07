package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ErasureRepository.CacheRow;
import com.example.starter.consent.ErasureRepository.TaskRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureTaskResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 旧代载荷清除服务：可重入的分批物理清除。
 *
 * <p>每次执行按 recordKey 字典序清除最多 {@value #BATCH_SIZE} 条旧代载荷（置空并打清除标记，
 * 记录身份保留），并同事务擦除该代 WRITE 幂等缓存快照中残留的原载荷、更新任务累计清除数；
 * 本代无未清项时任务转 DONE。只处理指定代，不访问其他用途或新代。
 *
 * <p>执行按 requestId 幂等：同参重放返回首次的无载荷计数，异参返回 409；失败请求不占键。
 * 清除与进度更新在同一事务，失败回滚不删半批。进度与执行响应均不含任何载荷。
 */
@Service
public class ErasureService {

    static final String CODE_TASK_NOT_FOUND = "ERASURE_TASK_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_ERASE = "ERASE";
    static final int BATCH_SIZE = 5;

    private final ErasureRepository erasureRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ErasureService(ErasureRepository erasureRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper) {
        this.erasureRepository = erasureRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一批清除：同 requestId 同参重放返回首次结果；任务不存在返回 404。
     */
    @Transactional
    public ErasureTaskResponse execute(ErasureExecuteRequest request) {
        String fingerprint = OP_ERASE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ErasureTaskResponse.class);
        }

        // 任务行锁串行化并发执行：后到事务等待并看到已提交进度，批次不重叠
        TaskRow task = erasureRepository.findTaskForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_TASK_NOT_FOUND, "清除任务不存在"));

        ErasureTaskResponse response;
        if (task.status() == ErasureTaskStatus.DONE) {
            // 已完成任务重复执行：只回报当前进度，不再触碰记录
            response = new ErasureTaskResponse(task.subjectKey(), task.purpose(), task.epoch(),
                    ErasureTaskStatus.DONE, task.erasedCount(), 0);
        } else {
            List<String> keys = erasureRepository.findUnerasedRecordKeys(
                    request.subjectKey(), request.purpose(), request.epoch(), BATCH_SIZE);
            for (String recordKey : keys) {
                int updated = erasureRepository.markPayloadErased(
                        request.subjectKey(), request.purpose(), request.epoch(), recordKey);
                if (updated != 1) {
                    // 清除与进度同事务：任何一条未命中都回滚整批，不删半批
                    throw new IllegalStateException("清除记录状态冲突，整批回滚: " + recordKey);
                }
            }
            scrubWriteCache(request.subjectKey(), request.purpose(), request.epoch());
            int remaining = erasureRepository.countRemaining(
                    request.subjectKey(), request.purpose(), request.epoch());
            int erasedTotal = task.erasedCount() + keys.size();
            boolean done = remaining == 0;
            erasureRepository.updateTaskProgress(
                    request.subjectKey(), request.purpose(), request.epoch(), erasedTotal, done);
            response = new ErasureTaskResponse(task.subjectKey(), task.purpose(), task.epoch(),
                    done ? ErasureTaskStatus.DONE : ErasureTaskStatus.PENDING, erasedTotal, remaining);
        }
        return storeSuccess(request.requestId(), fingerprint, response);
    }

    /**
     * 查看无载荷进度：只返回任务状态与计数，不包含任何记录载荷。
     */
    @Transactional(readOnly = true)
    public ErasureTaskResponse progress(String subjectKey, Purpose purpose, int epoch) {
        TaskRow task = erasureRepository.findTask(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_TASK_NOT_FOUND, "清除任务不存在"));
        int remaining = erasureRepository.countRemaining(subjectKey, purpose, epoch);
        return new ErasureTaskResponse(task.subjectKey(), task.purpose(), task.epoch(),
                task.status(), task.erasedCount(), remaining);
    }

    /**
     * 擦除该代 WRITE 幂等缓存快照中残留的原载荷：载荷置空，元数据与原成功身份保留。
     */
    private void scrubWriteCache(String subjectKey, Purpose purpose, int epoch) {
        for (CacheRow row : erasureRepository.findWriteCache(subjectKey, purpose, epoch)) {
            RecordResponse snapshot = readSnapshot(row.responseBody(), RecordResponse.class);
            if (!snapshot.payload().isEmpty()) {
                RecordResponse scrubbed = new RecordResponse(snapshot.subjectKey(), snapshot.purpose(),
                        snapshot.epoch(), snapshot.recordKey(), "");
                erasureRepository.scrubWriteCache(row.requestId(), writeSnapshot(scrubbed));
            }
        }
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    /**
     * 保存成功结果；并发同 requestId 时以已提交快照为准返回首次计数，不泄漏唯一键异常。
     */
    private ErasureTaskResponse storeSuccess(String requestId, String fingerprint, ErasureTaskResponse response) {
        try {
            idempotencyRepository.insert(requestId, OP_ERASE, fingerprint, writeSnapshot(response),
                    response.subjectKey(), response.purpose(), response.epoch(), null);
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
            return readSnapshot(committed.responseBody(), ErasureTaskResponse.class);
        }
        return response;
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
