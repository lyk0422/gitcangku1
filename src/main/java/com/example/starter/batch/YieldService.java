package com.example.starter.batch;

import com.example.starter.batch.dto.YieldDispositionRequest;
import com.example.starter.batch.dto.YieldDispositionResponse;
import com.example.starter.batch.dto.YieldHistoryResponse;
import com.example.starter.batch.dto.YieldReconcileRequest;
import com.example.starter.batch.dto.YieldReconcileResponse;
import com.example.starter.batch.dto.YieldReleaseRequest;
import com.example.starter.batch.dto.YieldReleaseResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 产率平衡核算核心服务。
 *
 * <p>偏差 = |投料 - (合格产出 + 损耗 + 报废)|，允许上限取批次建批时冻结的配置，
 * 恰好等于上限视为合格；超限版本进入 PENDING_DISPOSITION 锁定门禁，
 * 两名不同处置人确认同一版本后流转 DISPOSITION_CLEARED 解除门禁。
 *
 * <p>并发策略：核算提交、处置确认与放行均在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 放行裁决只认已提交的最新核算版本状态——新超限核算先提交则放行 422，放行先提交则新核算 409，
 * 不允许出现超限批次已放行。requestId/commandKey 幂等通过 command_log 主键 + 参数指纹实现：
 * 同键同参重放返回首次响应快照，同键改参返回 409；仅成功命令写入 command_log。
 */
@Service
public class YieldService {

    private static final String CMD_RECONCILE = "YIELD_RECONCILE";
    private static final String CMD_DISPOSITION = "YIELD_DISPOSITION";
    private static final String CMD_RELEASE = "YIELD_RELEASE";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public YieldService(BatchRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 提交四类数量进行产率平衡核算：任一数量为负返回 422 且不改动原核算；
     * 同 requestId 同数量重放返回首次偏差与状态，换数量返回 409；
     * 批次已放行后不能再提交核算（409）。
     */
    public StoredResponse submitReconciliation(String batchKey, YieldReconcileRequest req) {
        BigDecimal input = YieldCalculator.normalize(req.inputQuantity());
        BigDecimal qualified = YieldCalculator.normalize(req.qualifiedQuantity());
        BigDecimal loss = YieldCalculator.normalize(req.lossQuantity());
        BigDecimal scrap = YieldCalculator.normalize(req.scrapQuantity());
        String fingerprint = fingerprint("yield", batchKey, input.toPlainString(),
                qualified.toPlainString(), loss.toPlainString(), scrap.toPlainString());
        // requestId 作用域为批次内（与 uk_yield_request 的 (batch_key, request_id) 对齐），
        // command_log 为全局主键，故用 批次键:requestId 组合，避免跨批次同 requestId 误判冲突
        String commandKey = batchKey + ":" + req.requestId();
        return executeIdempotent(CMD_RECONCILE, commandKey, fingerprint, () -> {
            BatchRepository.BatchRow batch = repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = loggedResponse(CMD_RECONCILE, commandKey, fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (repo.findYieldRelease(batchKey).isPresent()) {
                throw ApiException.conflict("批次已完成产率放行，不能再提交核算: " + batchKey);
            }
            if (YieldCalculator.anyNegative(input, qualified, loss, scrap)) {
                // 前置条件失败：不落任何核算记录，原核算保持不变
                throw ApiException.unprocessable("投料、合格产出、损耗、报废数量均不能为负");
            }

            BigDecimal toleranceLimit = batch.toleranceLimit().setScale(YieldCalculator.SCALE);
            BigDecimal deviation = YieldCalculator.deviation(input, qualified, loss, scrap);
            YieldStatus status = YieldCalculator.withinLimit(deviation, toleranceLimit)
                    ? YieldStatus.WITHIN_LIMIT
                    : YieldStatus.PENDING_DISPOSITION;
            int version = repo.maxYieldVersion(batchKey) + 1;
            String now = now();
            repo.insertReconciliation(new BatchRepository.ReconciliationRow(0L, batchKey, version,
                    req.requestId(), input, qualified, loss, scrap, deviation, toleranceLimit,
                    status.name(), now));
            YieldReconcileResponse body = toResponse(
                    repo.findReconciliation(batchKey, req.requestId()).orElseThrow(),
                    repo.findYieldRelease(batchKey).orElse(null));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 处置确认：仅最新版本的超限核算可确认；处置版本过期（已被新核算替代）返回 422，
     * 不改动原核算。同一处置人重复确认同一版本幂等返回；第二名不同处置人确认后门禁解除。
     */
    public StoredResponse confirmDisposition(String batchKey, String handlerHeader,
                                             YieldDispositionRequest req) {
        if (handlerHeader == null || handlerHeader.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String handlerId = handlerHeader.trim();
        String fingerprint = fingerprint("disposition", batchKey, req.requestId(), handlerId);
        return executeIdempotent(CMD_DISPOSITION, req.commandKey(), fingerprint, () -> {
            repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_DISPOSITION, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (repo.findYieldRelease(batchKey).isPresent()) {
                throw ApiException.conflict("批次已完成产率放行，处置确认已关闭: " + batchKey);
            }
            BatchRepository.ReconciliationRow target = repo.findReconciliation(batchKey, req.requestId())
                    .orElseThrow(() -> ApiException.notFound(
                            "核算版本不存在: " + req.requestId()));
            BatchRepository.ReconciliationRow latest = repo.findLatestReconciliation(batchKey).orElseThrow();
            if (target.version() != latest.version()) {
                // 处置人确认的是过期版本：门禁以最新版本裁决，不改动任何核算与处置记录
                throw ApiException.unprocessable(
                        "处置版本已过期: requestId=" + req.requestId()
                                + " 对应版本 " + target.version() + "，当前最新版本 " + latest.version());
            }
            YieldStatus status = YieldStatus.valueOf(target.status());
            if (status == YieldStatus.WITHIN_LIMIT) {
                throw ApiException.unprocessable("该版本偏差未超限，无需处置确认");
            }

            List<BatchRepository.DispositionRow> confirmations =
                    repo.findDispositions(batchKey, target.version());
            // 同一处置人重复确认：业务幂等，不新增记录，返回当前版本状态
            Optional<BatchRepository.DispositionRow> own = confirmations.stream()
                    .filter(d -> d.handlerId().equals(handlerId)).findFirst();
            if (own.isPresent()) {
                return new StoredResponse(200, toJson(toResponse(target, null)));
            }
            if (status == YieldStatus.DISPOSITION_CLEARED) {
                throw ApiException.conflict("两名不同处置人已确认，门禁已解除");
            }

            int seq = confirmations.size() + 1;
            String now = now();
            repo.insertDisposition(new BatchRepository.DispositionRow(0L, batchKey, target.version(),
                    req.commandKey(), handlerId, seq, now));
            if (seq == 2) {
                repo.updateYieldStatus(batchKey, target.version(),
                        YieldStatus.DISPOSITION_CLEARED.name());
                target = repo.findReconciliationByVersion(batchKey, target.version()).orElseThrow();
            }
            return new StoredResponse(201, toJson(toResponse(target, null)));
        });
    }

    /**
     * 产率门禁放行：最新版本 WITHIN_LIMIT 或 DISPOSITION_CLEARED 才可放行；
     * PENDING_DISPOSITION（超限待处置）返回 422；尚未提交核算返回 422；
     * 已放行批次重复放行幂等返回首次放行结果。
     */
    public StoredResponse release(String batchKey, String actorHeader, YieldReleaseRequest req) {
        if (actorHeader == null || actorHeader.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actorId = actorHeader.trim();
        String fingerprint = fingerprint("release", batchKey, actorId);
        return executeIdempotent(CMD_RELEASE, req.commandKey(), fingerprint, () -> {
            repo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_RELEASE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            // 不同 commandKey 的重复放行：返回既有放行裁决，不新增记录
            Optional<BatchRepository.ReleaseRow> existing = repo.findYieldRelease(batchKey);
            if (existing.isPresent()) {
                return new StoredResponse(200, toJson(toReleaseResponse(existing.get())));
            }
            BatchRepository.ReconciliationRow latest = repo.findLatestReconciliation(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("尚未提交产率核算，不能放行"));
            YieldStatus status = YieldStatus.valueOf(latest.status());
            if (status == YieldStatus.PENDING_DISPOSITION) {
                throw ApiException.unprocessable(
                        "偏差 " + latest.deviation().toPlainString() + " 超过允许上限 "
                                + latest.toleranceLimit().toPlainString() + "，批次待处置不能放行");
            }
            String now = now();
            BatchRepository.ReleaseRow releaseRow = new BatchRepository.ReleaseRow(0L, batchKey,
                    latest.version(), req.commandKey(), actorId, now);
            repo.insertYieldRelease(releaseRow);
            return new StoredResponse(201, toJson(toReleaseResponse(
                    new BatchRepository.ReleaseRow(0L, batchKey, latest.version(),
                            req.commandKey(), actorId, now))));
        });
    }

    /**
     * 当前查询：最新核算版本的四类数量、偏差、冻结上限与处置状态；批次或核算不存在返回 404。
     */
    public YieldReconcileResponse current(String batchKey) {
        requireBatch(batchKey);
        BatchRepository.ReconciliationRow latest = repo.findLatestReconciliation(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次尚未提交产率核算: " + batchKey));
        return toResponse(latest, repo.findYieldRelease(batchKey).orElse(null));
    }

    /**
     * 历史查询：按版本号升序返回全部核算版本，每版保留计算时冻结的上限与两名处置人，不回写。
     */
    public YieldHistoryResponse history(String batchKey) {
        requireBatch(batchKey);
        BatchRepository.ReleaseRow release = repo.findYieldRelease(batchKey).orElse(null);
        List<YieldReconcileResponse> versions = new ArrayList<>();
        for (BatchRepository.ReconciliationRow row : repo.findReconciliations(batchKey)) {
            versions.add(toResponse(row, release));
        }
        return new YieldHistoryResponse(batchKey, versions);
    }

    // ---------- 内部辅助 ----------

    private void requireBatch(String batchKey) {
        repo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
    }

    private YieldReconcileResponse toResponse(BatchRepository.ReconciliationRow row,
                                              BatchRepository.ReleaseRow release) {
        List<YieldDispositionResponse> handlers = repo.findDispositions(row.batchKey(), row.version())
                .stream()
                .map(d -> new YieldDispositionResponse(d.handlerId(), d.seq(),
                        Instant.parse(d.createdAt())))
                .toList();
        boolean released = release != null && release.version() == row.version();
        // 数据库读回的 DECIMAL 可能被驱动去掉尾随零，响应统一三位小数输出
        return new YieldReconcileResponse(row.batchKey(), row.version(), row.requestId(),
                scale3(row.inputQuantity()), scale3(row.qualifiedQuantity()),
                scale3(row.lossQuantity()), scale3(row.scrapQuantity()),
                scale3(row.deviation()), scale3(row.toleranceLimit()),
                YieldStatus.valueOf(row.status()), handlers,
                released, released ? Instant.parse(release.releasedAt()) : null,
                Instant.parse(row.submittedAt()));
    }

    private YieldReleaseResponse toReleaseResponse(BatchRepository.ReleaseRow row) {
        BatchRepository.ReconciliationRow basis =
                repo.findReconciliationByVersion(row.batchKey(), row.version())
                        .orElseThrow(() -> new IllegalStateException(
                                "放行依据的核算版本不存在: " + row.version()));
        return new YieldReleaseResponse(row.batchKey(), row.version(), basis.requestId(),
                scale3(basis.deviation()), scale3(basis.toleranceLimit()), basis.status(),
                row.actorId(), Instant.parse(row.releasedAt()));
    }

    private static BigDecimal scale3(BigDecimal value) {
        return value.setScale(YieldCalculator.SCALE, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键/唯一约束冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
        var existing = repo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
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

    private String fingerprint(String... parts) {
        // NUL 分隔：业务参数不可能包含该字符，避免固定元数拼接碰撞
        String canonical = String.join(String.valueOf((char) 0), parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
