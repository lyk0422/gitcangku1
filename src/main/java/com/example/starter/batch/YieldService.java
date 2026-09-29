package com.example.starter.batch;

import com.example.starter.batch.dto.DisposeRequest;
import com.example.starter.batch.dto.DisposeResponse;
import com.example.starter.batch.dto.SubmitYieldRequest;
import com.example.starter.batch.dto.YieldCurrentResponse;
import com.example.starter.batch.dto.YieldVersionResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 产率平衡核算服务：提交核算版本、超限双人处置、当前查询。
 *
 * <p>同一批次上的提交/处置与放行批准共用 batch 行锁（SELECT ... FOR UPDATE）串行化，
 * 按事务提交顺序裁决：放行事务持锁后读到的 yield_status 即已提交的最新偏差门禁状态，
 * 绝无超限待处置批次被放行。核算版本与处置确认只增不改；requestId/commandKey
 * 幂等通过 command_log 主键 + 参数指纹实现，并由业务表唯一约束兜底并发。
 */
@Service
public class YieldService {

    private static final String CMD_SUBMIT_YIELD = "SUBMIT_YIELD";
    private static final String CMD_DISPOSE = "DISPOSE_YIELD";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final YieldRepository yieldRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public YieldService(YieldRepository yieldRepo,
                        BatchRepository batchRepo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.yieldRepo = yieldRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 提交四类数量进行产率平衡核算。
     * 任一数量为负返回 422 且不改动原核算；同 requestId 重试返回首次偏差与状态，换数量返回 409。
     * 每次提交生成新版本并成为批次最新版本，旧版本上的处置确认随之失效（版本过期）。
     */
    public StoredResponse submitYield(String batchKey, SubmitYieldRequest req) {
        BigDecimal input = YieldMath.normalize(req.inputQty());
        BigDecimal good = YieldMath.normalize(req.goodQty());
        BigDecimal loss = YieldMath.normalize(req.lossQty());
        BigDecimal scrap = YieldMath.normalize(req.scrapQty());
        if (input.signum() < 0 || good.signum() < 0 || loss.signum() < 0 || scrap.signum() < 0) {
            throw ApiException.unprocessable("投料、合格产出、损耗、报废数量均不允许为负");
        }
        String fingerprint = fingerprint("yield", batchKey, req.requestId(),
                input.toPlainString(), good.toPlainString(), loss.toPlainString(), scrap.toPlainString());
        return executeIdempotent(CMD_SUBMIT_YIELD, batchKey + ":" + req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交产率核算");
            }

            BigDecimal limit = new BigDecimal(batch.deviationLimit());
            BigDecimal deviation = YieldMath.deviation(input, good, loss, scrap);
            boolean within = YieldMath.withinLimit(deviation, limit);
            YieldGateStatus gate = within ? YieldGateStatus.WITHIN_LIMIT : YieldGateStatus.EXCEEDED_PENDING;
            String now = now();
            long version = yieldRepo.insertYield(new YieldRepository.YieldRow(0L, batchKey, req.requestId(),
                    input, good, loss, scrap, deviation, limit, within, now));
            yieldRepo.updateYieldStatus(batchKey, gate.name());

            YieldVersionResponse body = new YieldVersionResponse(version, req.requestId(),
                    input, good, loss, scrap, deviation, limit, within, gate, List.of(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 处置人确认超限偏差版本；两名不同处置人确认同一版本后解除门禁（DISPOSED）。
     * 版本不是批次最新版本（过期）返回 422 且不写入确认；同一处置人重复确认返回其首次确认结果。
     */
    public StoredResponse dispose(String batchKey, String disposerId, DisposeRequest req) {
        if (disposerId == null || disposerId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String disposer = disposerId.trim();
        long requestVersion = req.version();
        String fingerprint = fingerprint("dispose", batchKey, disposer, Long.toString(requestVersion));
        return executeIdempotent(CMD_DISPOSE, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertNoRecalledAncestor(batchKey);
            BatchStatus batchStatus = BatchStatus.valueOf(batch.status());
            if (batchStatus == BatchStatus.REJECTED || batchStatus == BatchStatus.RELEASED
                    || batchStatus == BatchStatus.RECALLED || batchStatus == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + batchStatus + " 不允许处置产率偏差");
            }
            YieldRepository.YieldRow latest = yieldRepo.findLatestYield(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次尚未提交产率核算，无版本可处置"));
            if (latest.id() != requestVersion) {
                throw ApiException.unprocessable("处置版本 " + requestVersion
                        + " 已过期，批次最新核算版本为 " + latest.id());
            }
            if (latest.withinLimit()) {
                throw ApiException.conflict("版本 " + requestVersion + " 偏差未超限，无需处置");
            }

            List<YieldRepository.DisposalRow> disposals = yieldRepo.findDisposals(requestVersion);
            Optional<YieldRepository.DisposalRow> own = disposals.stream()
                    .filter(d -> d.disposerId().equals(disposer)).findFirst();
            if (own.isPresent()) {
                // 同一处置人换 commandKey 重复确认：不新增记录，返回其首次确认结果（200）
                return new StoredResponse(200, toJson(toDisposeResponse(latest, disposer,
                        own.get().seq(), disposals, Instant.parse(own.get().createdAt()))));
            }
            if (disposals.size() >= 2) {
                throw ApiException.conflict("版本 " + requestVersion + " 已由两名不同处置人确认，门禁已解除");
            }

            int seq = disposals.size() + 1;
            YieldGateStatus gate = seq == 2 ? YieldGateStatus.DISPOSED : YieldGateStatus.EXCEEDED_PENDING;
            String now = now();
            yieldRepo.insertDisposal(new YieldRepository.DisposalRow(0L, batchKey, requestVersion,
                    req.commandKey(), disposer, seq, now));
            yieldRepo.updateYieldStatus(batchKey, gate.name());
            DisposeResponse body = toDisposeResponse(latest, disposer, seq,
                    yieldRepo.findDisposals(requestVersion), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 当前查询：最新版本四类数量、偏差、冻结上限与门禁处置状态；未提交核算时 gateStatus=NONE。
     */
    public YieldCurrentResponse current(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return yieldRepo.findLatestYield(batchKey).map(y -> new YieldCurrentResponse(
                batchKey, y.id(), y.requestId(),
                y.inputQty(), y.goodQty(), y.lossQty(), y.scrapQty(),
                y.deviation(), y.deviationLimit(),
                YieldGateStatus.valueOf(batch.yieldStatus())))
                .orElseGet(() -> new YieldCurrentResponse(batchKey, null, null,
                        null, null, null, null, null,
                        new BigDecimal(batch.deviationLimit()), YieldGateStatus.NONE));
    }

    /**
     * 批次全部核算历史版本（含各版本冻结上限快照与确认该版本的处置人），按版本号升序。
     * 历史只增不改，不随后续提交新版本或冻结上限调整回写。
     */
    public List<YieldVersionResponse> history(String batchKey) {
        List<YieldVersionResponse> result = new ArrayList<>();
        for (YieldRepository.YieldRow y : yieldRepo.findYields(batchKey)) {
            List<YieldRepository.DisposalRow> disposals = yieldRepo.findDisposals(y.id());
            List<String> disposerIds = disposals.stream().map(YieldRepository.DisposalRow::disposerId).toList();
            YieldGateStatus gate = gateOf(y, disposals);
            result.add(new YieldVersionResponse(y.id(), y.requestId(),
                    y.inputQty(), y.goodQty(), y.lossQty(), y.scrapQty(),
                    y.deviation(), y.deviationLimit(),
                    y.withinLimit(), gate, disposerIds, Instant.parse(y.createdAt())));
        }
        return result;
    }

    private DisposeResponse toDisposeResponse(YieldRepository.YieldRow version, String disposer,
                                              int seq, List<YieldRepository.DisposalRow> allDisposals,
                                              Instant createdAt) {
        List<String> disposerIds = allDisposals.stream()
                .map(YieldRepository.DisposalRow::disposerId).toList();
        YieldGateStatus gate = gateOf(version, allDisposals);
        return new DisposeResponse(version.batchKey(), version.id(), disposer, seq,
                disposerIds, gate, version.deviation(), version.deviationLimit(), createdAt);
    }

    /**
     * 版本门禁状态：未超限版本恒为 WITHIN_LIMIT；超限版本集齐两名不同处置人为 DISPOSED，否则待处置。
     */
    private YieldGateStatus gateOf(YieldRepository.YieldRow version,
                                   List<YieldRepository.DisposalRow> disposals) {
        if (version.withinLimit()) {
            return YieldGateStatus.WITHIN_LIMIT;
        }
        long distinctActors = disposals.stream().map(YieldRepository.DisposalRow::disposerId).distinct().count();
        return distinctActors >= 2 ? YieldGateStatus.DISPOSED : YieldGateStatus.EXCEEDED_PENDING;
    }

    /**
     * 若任一级祖先已被召回则抛 422：与检验/批准/拆分一致，后代禁止新增核算与处置。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止新增产率核算与处置");
            }
        }
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发唯一键冲突时回滚重试，读取已提交结果。
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
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键（command_log 主键或业务唯一约束）冲突：回滚后重试，读取对方已提交结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedResponse(String type, String commandKey, String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("命令键已以不同参数使用: " + commandKey);
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
        String canonical = String.join(SEP, parts);
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
