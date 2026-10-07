package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ErasureRepository.ErasureTaskRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ErasureExecuteRequest;
import com.example.starter.consent.dto.ErasureExecuteResponse;
import com.example.starter.consent.dto.ErasureProgressResponse;
import com.example.starter.consent.dto.RecordResponse;

/**
 * 清除域服务：撤回后对旧代合成载荷做可重入的物理清除。
 *
 * <p>每次执行按 recordKey 字典序清除最多 5 条，保留记录身份与清除标记，
 * 进度（累计清除数、状态）与载荷清除在同一事务提交，失败不删半批；
 * 无未清项时任务转 DONE。执行按 requestId 幂等，重放返回首次的无载荷计数。
 * 清除时同步把该代写入幂等快照中的载荷置空，缓存与指纹均不残留原文。
 * 仅本地角色 ERASER 可执行与查看进度，其他角色一律 403。
 */
@Service
public class ErasureService {

    static final String CODE_ERASURE_TASK_NOT_FOUND = "ERASURE_TASK_NOT_FOUND";
    static final String CODE_ERASER_ROLE_REQUIRED = "ERASER_ROLE_REQUIRED";
    static final String ROLE_ERASER = "ERASER";
    static final int BATCH_SIZE = 5;

    private static final String OP_ERASE = "ERASE";

    private final ErasureRepository erasureRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final IdempotencyGuard idempotencyGuard;

    public ErasureService(ErasureRepository erasureRepository,
                          ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          IdempotencyGuard idempotencyGuard) {
        this.erasureRepository = erasureRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * 执行一批清除：仅处理指定代次；重放返回首次结果，不重复清除。
     */
    @Transactional
    public ErasureExecuteResponse execute(ErasureExecuteRequest request) {
        requireEraser(request.role());
        String fingerprint = OP_ERASE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = idempotencyGuard.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencyGuard.readSnapshot(replayed.get().responseBody(), ErasureExecuteResponse.class);
        }

        ErasureTaskRow task = erasureRepository
                .findTaskForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_ERASURE_TASK_NOT_FOUND, "清除任务不存在"));

        int erasedNow = 0;
        int total = task.erasedCount();
        ErasureStatus status = task.status();
        if (status == ErasureStatus.PENDING) {
            List<String> keys = consentRepository.findPendingPayloadKeys(
                    request.subjectKey(), request.purpose(), request.epoch(), BATCH_SIZE);
            for (String recordKey : keys) {
                erasedNow += consentRepository.erasePayload(
                        request.subjectKey(), request.purpose(), request.epoch(), recordKey);
            }
            if (erasedNow > 0) {
                scrubWriteSnapshots(request.subjectKey(), request.purpose(), request.epoch());
            }
            total += erasedNow;
            if (consentRepository.countPendingPayloads(
                    request.subjectKey(), request.purpose(), request.epoch()) == 0) {
                status = ErasureStatus.DONE;
            }
            erasureRepository.updateProgress(
                    request.subjectKey(), request.purpose(), request.epoch(), total, status);
        }

        ErasureExecuteResponse response = new ErasureExecuteResponse(
                request.subjectKey(), request.purpose(), request.epoch(), status, total, erasedNow);
        idempotencyGuard.storeSuccess(request.requestId(), OP_ERASE, fingerprint, response,
                request.subjectKey(), request.purpose(), request.epoch());
        return response;
    }

    /**
     * 查看无载荷进度：仅状态与累计清除数，不含任何载荷原文。
     */
    @Transactional(readOnly = true)
    public ErasureProgressResponse progress(String subjectKey, Purpose purpose, int epoch, String role) {
        requireEraser(role);
        ErasureTaskRow task = erasureRepository.findTask(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_ERASURE_TASK_NOT_FOUND, "清除任务不存在"));
        return new ErasureProgressResponse(subjectKey, purpose, epoch, task.status(), task.erasedCount());
    }

    private void requireEraser(String role) {
        if (!ROLE_ERASER.equals(role)) {
            throw ApiException.forbidden(CODE_ERASER_ROLE_REQUIRED, "仅 ERASER 角色可执行清除与查看进度");
        }
    }

    /**
     * 把指定代次全部写入成功快照中的载荷置空，保留 requestId 与操作身份；撤回门禁使旧文本不可恢复。
     */
    private void scrubWriteSnapshots(String subjectKey, Purpose purpose, int epoch) {
        for (IdempotencyRow row : idempotencyRepository.findWriteRows(subjectKey, purpose, epoch)) {
            RecordResponse snapshot = idempotencyGuard.readSnapshot(row.responseBody(), RecordResponse.class);
            if (snapshot.payload() != null) {
                RecordResponse scrubbed = new RecordResponse(snapshot.subjectKey(), snapshot.purpose(),
                        snapshot.epoch(), snapshot.recordKey(), null);
                idempotencyRepository.updateResponseBody(row.requestId(), idempotencyGuard.writeSnapshot(scrubbed));
            }
        }
    }
}
