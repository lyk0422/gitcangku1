package com.example.starter.batch;

import com.example.starter.batch.dto.ScoreContribution;
import com.example.starter.batch.dto.ScoreTrajectoryPoint;
import com.example.starter.batch.dto.SetThresholdRequest;
import com.example.starter.batch.dto.SupplierScoreHistoryResponse;
import com.example.starter.batch.dto.SupplierScoreResponse;
import com.example.starter.batch.dto.ThresholdResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * 供应商评分卡与准入门槛服务。
 *
 * <p>评分为查询时实时计算的派生值：取供应商最近 20 个终态批次
 * （RELEASED 或 RECALLED，按批次创建时刻排序，创建时刻相同按提交顺序），
 * RECALLED 记 -10、RELEASED 记 +1，求和后裁剪到 [-100, 100]；不持久化可变分数字段。
 *
 * <p>门槛设置携带 requestId 幂等：同键同参重放首次结果，异参 409，失败不占键；
 * 门槛判定在批次创建事务内读取当前已提交的终态批次集合，按事务提交顺序裁决。
 */
@Service
public class SupplierService {

    /**
     * 滑动窗口大小：最近 20 个终态批次。
     */
    static final int WINDOW_SIZE = 20;

    static final int SCORE_MIN = -100;
    static final int SCORE_MAX = 100;
    static final int CONTRIBUTION_RELEASED = 1;
    static final int CONTRIBUTION_RECALLED = -10;

    private static final String CMD_SET_THRESHOLD = "SET_THRESHOLD";
    private static final String SEP = Character.toString((char) 0);
    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public SupplierService(BatchRepository repo,
                           PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 设置供应商准入门槛：立即生效于后续批次创建，不追溯影响已创建批次。
     */
    public StoredResponse setThreshold(String supplierId, SetThresholdRequest req) {
        String fingerprint = fingerprint("threshold", supplierId, String.valueOf(req.threshold()));
        return executeIdempotent(CMD_SET_THRESHOLD, req.requestId(), fingerprint, () -> {
            String now = Instant.now().toString();
            repo.upsertThreshold(new BatchRepository.ThresholdRow(supplierId, req.threshold(), now));
            ThresholdResponse body = new ThresholdResponse(supplierId, req.threshold(),
                    Instant.parse(now));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 供应商当前门槛配置；未设置时 threshold/updatedAt 为 null。
     */
    public ThresholdResponse thresholdConfig(String supplierId) {
        return repo.findThreshold(supplierId)
                .map(row -> new ThresholdResponse(row.supplierId(), row.threshold(),
                        Instant.parse(row.updatedAt())))
                .orElse(new ThresholdResponse(supplierId, null, null));
    }

    /**
     * 当前滑动评分明细：窗口批次清单按批次标识排序，含各自贡献分数。只读，不触发持久化。
     */
    public SupplierScoreResponse scoreDetail(String supplierId) {
        List<BatchRepository.BatchRow> window = currentWindow(supplierId);
        List<ScoreContribution> contributions = window.stream()
                .map(SupplierService::toContribution)
                .sorted(Comparator.comparing(ScoreContribution::batchKey))
                .toList();
        return new SupplierScoreResponse(supplierId, scoreOf(window), contributions.size(),
                contributions);
    }

    /**
     * 历史评分轨迹：按批次创建时刻升序，逐个终态批次落定后的滑动评分快照。只读。
     */
    public SupplierScoreHistoryResponse scoreHistory(String supplierId) {
        List<BatchRepository.BatchRow> terminal = terminalBatches(supplierId);
        List<ScoreTrajectoryPoint> trajectory = new ArrayList<>(terminal.size());
        Deque<BatchRepository.BatchRow> window = new ArrayDeque<>();
        int sum = 0;
        for (BatchRepository.BatchRow row : terminal) {
            window.addLast(row);
            sum += contributionOf(row);
            while (window.size() > WINDOW_SIZE) {
                sum -= contributionOf(window.pollFirst());
            }
            trajectory.add(new ScoreTrajectoryPoint(row.batchKey(), row.status(),
                    contributionOf(row), window.size(), clip(sum), Instant.parse(row.createdAt())));
        }
        return new SupplierScoreHistoryResponse(supplierId, trajectory);
    }

    /**
     * 当前滑动评分（门禁判定用）：基于调用事务内可读取到的最新已提交终态批次集合。
     */
    public int currentScore(String supplierId) {
        return scoreOf(currentWindow(supplierId));
    }

    private List<BatchRepository.BatchRow> currentWindow(String supplierId) {
        List<BatchRepository.BatchRow> terminal = terminalBatches(supplierId);
        int from = Math.max(0, terminal.size() - WINDOW_SIZE);
        return terminal.subList(from, terminal.size());
    }

    /**
     * 供应商全部终态批次，按批次创建时刻升序；创建时刻相同按提交顺序（id）裁决。
     */
    private List<BatchRepository.BatchRow> terminalBatches(String supplierId) {
        return repo.findTerminalBatchesBySupplier(supplierId).stream()
                .sorted(Comparator.comparing((BatchRepository.BatchRow r) -> Instant.parse(r.createdAt()))
                        .thenComparingLong(BatchRepository.BatchRow::id))
                .toList();
    }

    private static ScoreContribution toContribution(BatchRepository.BatchRow row) {
        return new ScoreContribution(row.batchKey(), row.status(), contributionOf(row),
                Instant.parse(row.createdAt()));
    }

    private static int contributionOf(BatchRepository.BatchRow row) {
        return BatchStatus.RECALLED.name().equals(row.status())
                ? CONTRIBUTION_RECALLED
                : CONTRIBUTION_RELEASED;
    }

    private static int scoreOf(List<BatchRepository.BatchRow> window) {
        int sum = 0;
        for (BatchRepository.BatchRow row : window) {
            sum += contributionOf(row);
        }
        return clip(sum);
    }

    private static int clip(int score) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, score));
    }

    /**
     * 幂等执行：与批次命令同一套 command_log 机制；仅成功命令占键。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var existing = repo.findCommand(type, commandKey);
                    if (existing.isPresent()) {
                        BatchRepository.CommandRow row = existing.get();
                        if (!row.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict(
                                    "requestId 已以不同参数使用: " + commandKey);
                        }
                        return new StoredResponse(row.responseStatus(), row.responseBody());
                    }
                    StoredResponse response = action.get();
                    repo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), Instant.now().toString());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键冲突：回滚后重试，读取对方已提交的命令快照
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
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
