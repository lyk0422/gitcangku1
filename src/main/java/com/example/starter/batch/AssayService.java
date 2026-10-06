package com.example.starter.batch;

import com.example.starter.batch.dto.AssayReadingResponse;
import com.example.starter.batch.dto.AssayRoundDetailResponse;
import com.example.starter.batch.dto.AssayRoundResponse;
import com.example.starter.batch.dto.OpenAssayRoundRequest;
import com.example.starter.batch.dto.WriteAssayReadingRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平行测定服务：隔离批次为必需检验项建立测定轮次，固定 3 个读数，
 * 均值闭区间与最大极差判定；读数齐全自动封结，封结后不可覆写；
 * 不合格可开新轮次，每项最多 3 轮；新轮开始使该项旧批准失效。
 *
 * <p>并发策略：与检验/批准/召回共用批次行锁（SELECT ... FOR UPDATE）串行化，
 * 轮次行锁串行化读数写入；expectedVersion 为乐观锁，不符返回 409 且不留读数占位；
 * requestId 幂等身份为 操作+操作者+资源+全部参数+期望版本 的结构化 JSON 摘要，
 * 同键同参重放首次完整响应，异参 409，失败不占键。
 */
@Service
public class AssayService {

    static final String CMD_ROUND_OPEN = "ASSAY_ROUND_OPEN";
    static final String CMD_READING_WRITE = "ASSAY_READING_WRITE";

    /**
     * 每个检验项最多允许的测定轮次数。
     */
    static final int MAX_ROUNDS_PER_ITEM = 3;

    /**
     * 每轮固定读数条数。
     */
    static final int READINGS_PER_ROUND = 3;

    /**
     * 十进制字符串格式：可选负号，最多 4 位小数。
     */
    private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,18}(\\.\\d{1,4})?");

    private final BatchRepository batchRepo;
    private final AssayRepository assayRepo;
    private final BatchService batchService;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public AssayService(BatchRepository batchRepo, AssayRepository assayRepo,
                        BatchService batchService, IdempotentExecutor idempotent,
                        ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.assayRepo = assayRepo;
        this.batchService = batchService;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 开新轮次：批次须为 QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW（已放行不可开轮），
     * 检验项须为必做项且无旧式检验结果；当前轮未封结不可开新轮；每项最多 3 轮；
     * 在 PENDING_RELEASE/RELEASE_REVIEW 开轮使批次回退 QUARANTINED 并使旧批准失效。
     */
    public StoredResponse openRound(String batchKey, OpenAssayRoundRequest req) {
        BigDecimal meanMin = parseDecimal("meanMin", req.meanMin());
        BigDecimal meanMax = parseDecimal("meanMax", req.meanMax());
        BigDecimal maxRange = parseDecimal("maxRange", req.maxRange());
        if (meanMin.compareTo(meanMax) > 0) {
            throw ApiException.badRequest("meanMin 不能大于 meanMax");
        }
        if (maxRange.signum() < 0) {
            throw ApiException.badRequest("maxRange 不能为负");
        }
        String operator = req.operator().trim();
        String testItem = req.testItem().trim();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", CMD_ROUND_OPEN);
        identity.put("operator", operator);
        identity.put("batchKey", batchKey);
        identity.put("testItem", testItem);
        identity.put("meanMin", req.meanMin().trim());
        identity.put("meanMax", req.meanMax().trim());
        identity.put("maxRange", req.maxRange().trim());
        identity.put("expectedVersion", req.expectedVersion());
        String fingerprint = structuredFingerprint(identity);
        return idempotent.execute(CMD_ROUND_OPEN, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.loggedResponse(CMD_ROUND_OPEN, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            batchService.assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                    && status != BatchStatus.RELEASE_REVIEW) {
                throw ApiException.conflict("批次状态 " + status + " 不允许开测定轮次，已放行不可开轮");
            }
            List<String> required = batchRepo.findRequiredTests(batchKey);
            if (!required.contains(testItem)) {
                throw ApiException.unprocessable("检验项不属于该批次必做项: " + testItem);
            }
            boolean itemTested = batchRepo.findTests(batchKey).stream()
                    .anyMatch(t -> t.testItem().equals(testItem));
            if (itemTested) {
                throw ApiException.conflict("该检验项已存在旧式检验结果，不能开测定轮次: " + testItem);
            }
            List<AssayRepository.RoundRow> rounds = assayRepo.findRounds(batchKey, testItem);
            if (req.expectedVersion() != rounds.size()) {
                throw ApiException.conflict("expectedVersion 不符: 期望 " + req.expectedVersion()
                        + "，当前轮次数 " + rounds.size());
            }
            if (rounds.size() >= MAX_ROUNDS_PER_ITEM) {
                throw ApiException.conflict("该检验项已达最多 " + MAX_ROUNDS_PER_ITEM + " 轮，不能再开新轮次");
            }
            if (!rounds.isEmpty()
                    && AssayRoundStatus.OPEN.name().equals(rounds.get(rounds.size() - 1).status())) {
                throw ApiException.conflict("当前轮次未封结，不能开新轮次");
            }
            String now = Instant.now().toString();
            int roundNo = rounds.size() + 1;
            assayRepo.insertRound(new AssayRepository.RoundRow(0L, batchKey, testItem, roundNo,
                    req.meanMin().trim(), req.meanMax().trim(), req.maxRange().trim(),
                    0, AssayRoundStatus.OPEN.name(), operator, now, null));
            AssayRepository.RoundRow created = assayRepo.findRounds(batchKey, testItem)
                    .get(roundNo - 1);
            if (status == BatchStatus.PENDING_RELEASE || status == BatchStatus.RELEASE_REVIEW) {
                // 新轮开始使该项旧批准失效：批次回退隔离，旧批准记录删除，不能用旧轮合格授权新轮
                batchRepo.deleteApprovals(batchKey);
                batchRepo.updateStatus(batchKey, BatchStatus.QUARANTINED.name());
            }
            return new StoredResponse(201, toJson(toRoundResponse(created)));
        });
    }

    /**
     * 写读数：轮次须 OPEN 且属于该批次；expectedVersion 须等于当前版本；
     * seq 轮次内唯一；第三条读数写入即封结判定，合格且全部必做项通过时批次进入
     * PENDING_RELEASE。失败不留读数占位。
     */
    public StoredResponse writeReading(String batchKey, long roundId, WriteAssayReadingRequest req) {
        BigDecimal value = parseDecimal("value", req.value());
        String operator = req.operator().trim();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", CMD_READING_WRITE);
        identity.put("operator", operator);
        identity.put("batchKey", batchKey);
        identity.put("roundId", roundId);
        identity.put("seq", req.seq());
        identity.put("value", req.value().trim());
        identity.put("expectedVersion", req.expectedVersion());
        String fingerprint = structuredFingerprint(identity);
        return idempotent.execute(CMD_READING_WRITE, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 批次行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.loggedResponse(CMD_READING_WRITE, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            batchService.assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                    && status != BatchStatus.RELEASE_REVIEW) {
                throw ApiException.conflict("批次状态 " + status + " 不允许写入测定读数");
            }
            AssayRepository.RoundRow round = assayRepo.findRoundForUpdate(roundId)
                    .filter(r -> r.batchKey().equals(batchKey))
                    .orElseThrow(() -> ApiException.notFound(
                            "测定轮次不存在: " + roundId + " (批次 " + batchKey + ")"));
            if (!AssayRoundStatus.OPEN.name().equals(round.status())) {
                throw ApiException.conflict("轮次已封结，读数不可覆写: " + roundId);
            }
            if (req.expectedVersion() != round.version()) {
                throw ApiException.conflict("expectedVersion 不符: 期望 " + req.expectedVersion()
                        + "，当前版本 " + round.version());
            }
            boolean seqTaken = assayRepo.findReadings(roundId).stream()
                    .anyMatch(r -> r.seq() == req.seq());
            if (seqTaken) {
                throw ApiException.conflict("读数序号已占用: " + req.seq());
            }
            String now = Instant.now().toString();
            assayRepo.insertReading(new AssayRepository.ReadingRow(0L, roundId, batchKey,
                    req.seq(), req.value().trim(), operator, now));
            int newVersion = round.version() + 1;
            assayRepo.updateRoundVersion(roundId, newVersion);

            AssayRoundStatus roundStatus = AssayRoundStatus.OPEN;
            String mean = null;
            BatchStatus newBatchStatus = status;
            if (newVersion == READINGS_PER_ROUND) {
                // 读数齐全即封结：均值闭区间 + 最大极差判定，比较不舍入、不先取整
                List<AssayRepository.ReadingRow> readings = assayRepo.findReadings(roundId);
                roundStatus = evaluate(round, readings) ? AssayRoundStatus.SEALED_PASS
                        : AssayRoundStatus.SEALED_FAIL;
                assayRepo.sealRound(roundId, roundStatus.name(), now);
                mean = meanForDisplay(readings);
                if (roundStatus == AssayRoundStatus.SEALED_PASS && status == BatchStatus.QUARANTINED
                        && batchService.allRequiredPassed(batchKey,
                                batchRepo.findRequiredTests(batchKey))) {
                    newBatchStatus = BatchStatus.PENDING_RELEASE;
                    batchRepo.updateStatus(batchKey, newBatchStatus.name());
                }
            }
            AssayReadingResponse body = new AssayReadingResponse(roundId, batchKey, round.testItem(),
                    round.roundNo(), req.seq(), req.value().trim(), operator, newVersion,
                    roundStatus, mean, newBatchStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 查询批次全部轮次及读数明细。
     */
    public List<AssayRoundDetailResponse> listRounds(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return assayRepo.findRoundsByBatch(batchKey).stream()
                .map(round -> new AssayRoundDetailResponse(toRoundResponse(round),
                        assayRepo.findReadings(round.id()).stream()
                                .map(r -> new com.example.starter.batch.dto.AssayReadingEntry(
                                        r.seq(), r.value(), r.operator(), Instant.parse(r.createdAt())))
                                .toList()))
                .toList();
    }

    /**
     * 封结判定：均值落入闭区间且最大减最小不超过最大极差。
     * 为避免除法舍入，均值比较等价转换为 3×区间与读数和的精确比较，不舍入、不先取整。
     */
    private boolean evaluate(AssayRepository.RoundRow round, List<AssayRepository.ReadingRow> readings) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        for (AssayRepository.ReadingRow reading : readings) {
            BigDecimal v = new BigDecimal(reading.value());
            sum = sum.add(v);
            min = min == null || v.compareTo(min) < 0 ? v : min;
            max = max == null || v.compareTo(max) > 0 ? v : max;
        }
        BigDecimal three = BigDecimal.valueOf(READINGS_PER_ROUND);
        BigDecimal lower = new BigDecimal(round.meanMin()).multiply(three);
        BigDecimal upper = new BigDecimal(round.meanMax()).multiply(three);
        boolean meanInRange = sum.compareTo(lower) >= 0 && sum.compareTo(upper) <= 0;
        boolean rangeOk = max.subtract(min).compareTo(new BigDecimal(round.maxRange())) <= 0;
        return meanInRange && rangeOk;
    }

    /**
     * 算术均值显示值（最多 10 位小数，仅展示用；判定不使用该值）。
     */
    private String meanForDisplay(List<AssayRepository.ReadingRow> readings) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AssayRepository.ReadingRow reading : readings) {
            sum = sum.add(new BigDecimal(reading.value()));
        }
        return sum.divide(BigDecimal.valueOf(READINGS_PER_ROUND), 10,
                java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 解析十进制字符串参数：最多 4 位小数，非法抛 400。
     */
    private BigDecimal parseDecimal(String field, String raw) {
        if (raw == null || !DECIMAL.matcher(raw.trim()).matches()) {
            throw ApiException.badRequest(field + " 必须为最多 4 位小数的十进制字符串: " + raw);
        }
        return new BigDecimal(raw.trim());
    }

    /**
     * 结构化幂等指纹：身份各字段按固定顺序组成 JSON 后取 SHA-256，
     * 结构化编码避免分隔符碰撞。
     */
    private String structuredFingerprint(Map<String, Object> identity) {
        String canonical = toJson(identity);
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

    private AssayRoundResponse toRoundResponse(AssayRepository.RoundRow row) {
        return new AssayRoundResponse(row.id(), row.batchKey(), row.testItem(), row.roundNo(),
                row.meanMin(), row.meanMax(), row.maxRange(), row.version(),
                AssayRoundStatus.valueOf(row.status()), row.createdBy(),
                Instant.parse(row.createdAt()),
                row.sealedAt() == null ? null : Instant.parse(row.sealedAt()));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
