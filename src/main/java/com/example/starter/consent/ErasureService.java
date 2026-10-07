package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ErasureRepository.ErasureTaskRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureTaskResponse;

/**
 * 清除任务域服务：撤回后为旧代次物理清除合成载荷。
 *
 * <p>规则：仅本地角色 ERASER 可执行与查看进度，其他角色 403；每次执行按 recordKey 字典序
 * 最多清除 5 条，保留记录身份与清除标记，累计清除数与任务状态同事务推进，失败不删半批；
 * 无未清项时任务转 DONE；只处理指定代次，不访问其他用途或新代；执行重放返回首次无载荷计数。
 */
@Service
public class ErasureService {

    static final String CODE_ERASURE_TASK_NOT_FOUND = "ERASURE_TASK_NOT_FOUND";
    static final String CODE_ERASER_ROLE_REQUIRED = "ERASER_ROLE_REQUIRED";

    static final String ERASER_ROLE = "ERASER";
    static final int BATCH_SIZE = 5;

    private static final String OP_ERASE = "ERASE";

    private final ErasureRepository erasureRepository;
    private final IdempotencyStore idempotencyStore;

    public ErasureService(ErasureRepository erasureRepository, IdempotencyStore idempotencyStore) {
        this.erasureRepository = erasureRepository;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 执行一批清除：锁定任务行后按字典序清除最多 5 条未清载荷，同事务更新累计数与状态。
     */
    @Transactional
    public ErasureTaskResponse execute(String role, ErasureExecuteRequest request) {
        requireEraserRole(role);
        String fingerprint = OP_ERASE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = idempotencyStore.find(request.requestId());
        if (replayed.isPresent()) {
            idempotencyStore.requireSameParams(replayed.get(), fingerprint);
            return idempotencyStore.readSnapshot(replayed.get().responseBody(), ErasureTaskResponse.class);
        }

        ErasureTaskRow task = erasureRepository
                .findTaskForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_ERASURE_TASK_NOT_FOUND, "清除任务不存在"));

        List<String> keys = erasureRepository.findPendingRecordKeys(
                request.subjectKey(), request.purpose(), request.epoch(), BATCH_SIZE);
        int erased = 0;
        for (String recordKey : keys) {
            erased += erasureRepository.erasePayload(
                    request.subjectKey(), request.purpose(), request.epoch(), recordKey);
        }
        int total = task.erasedCount() + erased;
        int remaining = erasureRepository.countPending(request.subjectKey(), request.purpose(), request.epoch());
        ErasureStatus status = remaining == 0 ? ErasureStatus.DONE : ErasureStatus.PENDING;
        erasureRepository.updateTaskProgress(request.subjectKey(), request.purpose(), request.epoch(), total, status);

        ErasureTaskResponse response = new ErasureTaskResponse(request.subjectKey(), request.purpose(),
                request.epoch(), status, total, remaining);
        idempotencyStore.storeSuccess(request.requestId(), OP_ERASE, fingerprint, response,
                request.subjectKey(), request.purpose(), request.epoch());
        return response;
    }

    /**
     * 查看无载荷进度：仅返回状态与计数，不含任何记录内容。
     */
    @Transactional(readOnly = true)
    public ErasureTaskResponse progress(String role, String subjectKey, Purpose purpose, int epoch) {
        requireEraserRole(role);
        ErasureTaskRow task = erasureRepository.findTask(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_ERASURE_TASK_NOT_FOUND, "清除任务不存在"));
        int remaining = erasureRepository.countPending(subjectKey, purpose, epoch);
        return new ErasureTaskResponse(subjectKey, purpose, epoch, task.status(), task.erasedCount(), remaining);
    }

    private void requireEraserRole(String role) {
        if (!ERASER_ROLE.equals(role)) {
            throw ApiException.forbidden(CODE_ERASER_ROLE_REQUIRED, "仅 ERASER 角色可执行清除与查看进度");
        }
    }
}
