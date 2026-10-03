package com.example.starter.curtailment.service;

import com.example.starter.curtailment.api.ApiDtos;
import com.example.starter.curtailment.api.ApiException;
import com.example.starter.curtailment.domain.Instruction;
import com.example.starter.curtailment.domain.InstructionStatus;
import com.example.starter.curtailment.domain.PowerSample;
import com.example.starter.curtailment.domain.Reconciliation;
import com.example.starter.curtailment.domain.ReconciliationTick;
import com.example.starter.curtailment.repo.IdempotencyRepository;
import com.example.starter.curtailment.repo.InstructionRepository;
import com.example.starter.curtailment.repo.ReconciliationRepository;
import com.example.starter.curtailment.repo.SampleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 限发领域服务：指令生命周期、采样上报与迟到更正、对账与差额记录。
 * 并发约束：同一指令的确认/撤销/采样/对账通过指令行锁（SELECT ... FOR UPDATE）串行化；
 * 同机组创建指令通过机组锁行串行化；幂等按（操作, requestId）唯一键保证。
 */
@Service
public class CurtailmentService {

    /** 单个刻度时长（秒）：15 分钟。 */
    static final long TICK_SECONDS = 900;
    /** 对账窗口刻度数：4 个 15 分钟刻度，即 1 小时。 */
    static final int WINDOW_TICKS = 4;
    /** 每刻度电量系数：kW * 0.25h = kWh。 */
    static final BigDecimal ENERGY_PER_TICK = new BigDecimal("0.25");

    private final InstructionRepository instructionRepository;
    private final SampleRepository sampleRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CurtailmentService(InstructionRepository instructionRepository, SampleRepository sampleRepository,
                              ReconciliationRepository reconciliationRepository,
                              IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper, Clock clock) {
        this.instructionRepository = instructionRepository;
        this.sampleRepository = sampleRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建限发指令：同机组有效指令区间不得重叠，按机组锁行串行化检查。
     */
    @Transactional
    public ApiDtos.InstructionView createInstruction(ApiDtos.CreateInstructionRequest request) {
        Instant now = clock.instant();
        if (!request.endUtc().isAfter(request.startUtc())) {
            throw ApiException.unprocessable("INVALID_INTERVAL", "区间终点必须晚于起点", List.of());
        }
        if (request.startUtc().getEpochSecond() % TICK_SECONDS != 0 || request.startUtc().getNano() != 0
                || request.endUtc().getEpochSecond() % TICK_SECONDS != 0 || request.endUtc().getNano() != 0) {
            throw ApiException.unprocessable("NOT_ALIGNED", "区间起止必须 15 分钟对齐", List.of());
        }
        instructionRepository.lockUnit(request.unitId(), now);
        if (instructionRepository.existsActiveOverlap(request.unitId(), request.startUtc(), request.endUtc())) {
            throw ApiException.conflict("INSTRUCTION_OVERLAP", "同机组存在区间重叠的有效指令");
        }
        String instructionNo = request.instructionNo() == null || request.instructionNo().isBlank()
                ? "INS-" + Long.toHexString(now.toEpochMilli()) + "-" + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000))
                : request.instructionNo();
        long id;
        try {
            id = instructionRepository.insert(instructionNo, request.unitId(), request.startUtc(), request.endUtc(),
                    request.maxPowerKw(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("INSTRUCTION_NO_DUPLICATE", "指令编号已存在: " + instructionNo);
        }
        return toView(instructionRepository.findById(id).orElseThrow(), now);
    }

    /**
     * 机组确认：须在区间开始前；requestId 同参重放、异参 409；与撤销竞争时只允许一种终态。
     */
    @Transactional
    public ApiDtos.InstructionView confirm(long instructionId, ApiDtos.ConfirmRequest request) {
        return executeIdempotent("CONFIRM", request.requestId(), fingerprint(instructionId),
                ApiDtos.InstructionView.class, () -> {
                    Instant now = clock.instant();
                    Instruction instruction = lockInstruction(instructionId);
                    InstructionStatus status = instruction.effectiveStatus(now);
                    if (status == InstructionStatus.REVOKED) {
                        throw ApiException.conflict("INSTRUCTION_REVOKED", "指令已撤销，不能确认");
                    }
                    if (status != InstructionStatus.PENDING) {
                        throw ApiException.conflict("INSTRUCTION_NOT_PENDING", "指令已确认，不能重复确认");
                    }
                    if (!now.isBefore(instruction.startUtc())) {
                        throw ApiException.unprocessable("CONFIRM_TOO_LATE", "确认必须早于区间开始", List.of());
                    }
                    if (instructionRepository.transitionStatus(instructionId, List.of("PENDING"), "CONFIRMED", now) == 0) {
                        throw ApiException.conflict("STATUS_RACE", "指令状态已被并发修改");
                    }
                    return toView(instructionRepository.findById(instructionId).orElseThrow(), now);
                });
    }

    /**
     * 撤销指令：PENDING/CONFIRMED 可撤销；已撤销时重放当前状态；与确认竞争只允许一种终态。
     */
    @Transactional
    public ApiDtos.InstructionView revoke(long instructionId, ApiDtos.RevokeRequest request) {
        return executeIdempotent("REVOKE", request.requestId(), fingerprint(instructionId),
                ApiDtos.InstructionView.class, () -> {
                    Instant now = clock.instant();
                    Instruction instruction = lockInstruction(instructionId);
                    if (instruction.effectiveStatus(now) == InstructionStatus.REVOKED) {
                        return toView(instruction, now);
                    }
                    if (instructionRepository.transitionStatus(instructionId, List.of("PENDING", "CONFIRMED"),
                            "REVOKED", now) == 0) {
                        throw ApiException.conflict("STATUS_RACE", "指令状态已被并发修改");
                    }
                    return toView(instructionRepository.findById(instructionId).orElseThrow(), now);
                });
    }

    /**
     * 采样上报/迟到更正：同一刻度再次上报（不同 requestId）即追加更正版本。
     * 刻度必须 15 分钟对齐且落在指令区间内；指令须已确认。更正触发指令版本递增；
     * 若刻度已被对账覆盖，则保留原对账并追加差额记录，已结束指令不因此恢复运行中。
     */
    @Transactional
    public ApiDtos.SampleView reportSample(long instructionId, ApiDtos.SampleRequest request) {
        String fingerprint = fingerprint(instructionId, request.tickStart(), request.avgPowerKw());
        return executeIdempotent("SAMPLE", request.requestId(), fingerprint, ApiDtos.SampleView.class, () -> {
            Instant now = clock.instant();
            if (!isAligned(request.tickStart())) {
                throw ApiException.unprocessable("NOT_ALIGNED", "刻度必须 15 分钟对齐",
                        List.of(new ApiDtos.ProblemTick(request.tickStart(), "NOT_ALIGNED", "刻度未按 15 分钟对齐")));
            }
            Instruction instruction = lockInstruction(instructionId);
            InstructionStatus status = instruction.effectiveStatus(now);
            if (status != InstructionStatus.CONFIRMED && status != InstructionStatus.ENDED) {
                throw ApiException.unprocessable("NOT_CONFIRMED", "指令未确认或已撤销，不能上报采样",
                        List.of(new ApiDtos.ProblemTick(request.tickStart(), "NOT_CONFIRMED", "指令状态: " + status)));
            }
            if (request.tickStart().isBefore(instruction.startUtc()) || !request.tickStart().isBefore(instruction.endUtc())) {
                throw ApiException.unprocessable("OUT_OF_INTERVAL", "刻度不在指令区间内",
                        List.of(new ApiDtos.ProblemTick(request.tickStart(), "OUT_OF_INTERVAL", "刻度超出指令区间")));
            }
            int nextVersion = sampleRepository.currentVersion(instructionId, request.tickStart()) + 1;
            long sampleId = sampleRepository.insert(instructionId, instruction.unitId(), request.tickStart(),
                    request.avgPowerKw(), nextVersion, request.requestId(), now);
            if (nextVersion > 1) {
                instructionRepository.bumpVersion(instructionId, now);
                reconciliationRepository.findCoveringTick(instructionId, request.tickStart()).ifPresent(recon -> {
                    ReconciliationTick fixed = reconciliationRepository.findTicks(recon.id()).stream()
                            .filter(t -> t.tickStart().equals(request.tickStart())).findFirst()
                            .orElseThrow(() -> new IllegalStateException("对账刻度缺失"));
                    BigDecimal delta = request.avgPowerKw().subtract(fixed.avgPowerKw()).multiply(ENERGY_PER_TICK);
                    reconciliationRepository.insertAdjustment(recon.id(), instructionId, request.tickStart(),
                            fixed.avgPowerKw(), request.avgPowerKw(), delta, nextVersion, now);
                });
            }
            return new ApiDtos.SampleView(sampleId, instructionId, request.tickStart(), request.avgPowerKw(), nextVersion);
        });
    }

    /**
     * 对账：固化指令版本、窗口内四个采样刻度与累计电量。
     * 缺采样、超上限、刻度不对齐或指令未确认时整次拒绝并返回问题刻度，不生成部分结果；
     * 同窗口已存在对账单时直接返回既有结果，不重复累计电量。
     */
    @Transactional
    public ApiDtos.ReconciliationView reconcile(long instructionId, ApiDtos.ReconcileRequest request) {
        String fingerprint = fingerprint(instructionId, request.windowStart());
        return executeIdempotent("RECONCILE", request.requestId(), fingerprint, ApiDtos.ReconciliationView.class, () -> {
            Instant now = clock.instant();
            if (!isAligned(request.windowStart())) {
                throw ApiException.unprocessable("NOT_ALIGNED", "窗口起点必须 15 分钟对齐",
                        List.of(new ApiDtos.ProblemTick(request.windowStart(), "NOT_ALIGNED", "窗口起点未按 15 分钟对齐")));
            }
            Instruction instruction = lockInstruction(instructionId);
            InstructionStatus status = instruction.effectiveStatus(now);
            List<Instant> ticks = windowTicks(request.windowStart());
            if (status != InstructionStatus.CONFIRMED && status != InstructionStatus.ENDED) {
                throw ApiException.unprocessable("NOT_CONFIRMED", "指令未确认，不能对账",
                        problems(ticks, "NOT_CONFIRMED", "指令状态: " + status));
            }
            Instant windowEnd = request.windowStart().plusSeconds(TICK_SECONDS * WINDOW_TICKS);
            if (request.windowStart().isBefore(instruction.startUtc()) || windowEnd.isAfter(instruction.endUtc())) {
                throw ApiException.unprocessable("OUT_OF_INTERVAL", "对账窗口超出指令区间",
                        problems(ticks, "OUT_OF_INTERVAL", "窗口超出指令区间"));
            }
            var existing = reconciliationRepository.findByWindow(instructionId, request.windowStart());
            if (existing.isPresent()) {
                return toView(existing.get());
            }
            List<ApiDtos.ProblemTick> problems = new ArrayList<>();
            List<PowerSample> samples = new ArrayList<>();
            for (Instant tick : ticks) {
                var sample = sampleRepository.findLatest(instructionId, tick);
                if (sample.isEmpty()) {
                    problems.add(new ApiDtos.ProblemTick(tick, "MISSING_SAMPLE", "缺少该刻度采样"));
                } else if (sample.get().avgPowerKw().compareTo(instruction.maxPowerKw()) > 0) {
                    problems.add(new ApiDtos.ProblemTick(tick, "EXCEEDS_CAP",
                            "平均功率 " + sample.get().avgPowerKw() + " kW 超过上限 " + instruction.maxPowerKw() + " kW"));
                } else {
                    samples.add(sample.get());
                }
            }
            if (!problems.isEmpty()) {
                throw ApiException.unprocessable("RECONCILE_REJECTED", "对账被拒绝，存在 " + problems.size() + " 个问题刻度",
                        problems);
            }
            BigDecimal total = samples.stream()
                    .map(s -> s.avgPowerKw().multiply(ENERGY_PER_TICK))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long reconciliationId;
            try {
                reconciliationId = reconciliationRepository.insertReconciliation(instructionId, request.windowStart(),
                        instruction.version(), total, request.requestId(), now);
            } catch (DuplicateKeyException e) {
                // 并发对账同窗口：唯一约束兜底，返回已提交的对账单
                return toView(reconciliationRepository.findByWindow(instructionId, request.windowStart()).orElseThrow());
            }
            for (PowerSample sample : samples) {
                reconciliationRepository.insertTick(reconciliationId, sample.tickStart(), sample.avgPowerKw(),
                        sample.sampleVersion(), sample.avgPowerKw().multiply(ENERGY_PER_TICK));
            }
            return toView(reconciliationRepository.findByWindow(instructionId, request.windowStart()).orElseThrow());
        });
    }

    /** 查询指令详情：指令版本、全部采样刻度版本、对账单（含刻度快照）与差额链。 */
    @Transactional(readOnly = true)
    public ApiDtos.InstructionDetailView getDetail(long instructionId) {
        Instant now = clock.instant();
        Instruction instruction = instructionRepository.findById(instructionId)
                .orElseThrow(() -> ApiException.notFound("指令不存在: " + instructionId));
        List<ApiDtos.SampleView> samples = sampleRepository.findByInstruction(instructionId).stream()
                .map(s -> new ApiDtos.SampleView(s.id(), s.instructionId(), s.tickStart(), s.avgPowerKw(), s.sampleVersion()))
                .toList();
        List<ApiDtos.ReconciliationView> reconciliations = reconciliationRepository.findByInstruction(instructionId)
                .stream().map(this::toView).toList();
        List<ApiDtos.AdjustmentView> adjustments = reconciliationRepository.findAdjustmentsByInstruction(instructionId)
                .stream()
                .map(a -> new ApiDtos.AdjustmentView(a.id(), a.reconciliationId(), a.tickStart(), a.oldAvgPowerKw(),
                        a.newAvgPowerKw(), a.deltaEnergyKwh(), a.sampleVersion(), a.createdAt()))
                .toList();
        return new ApiDtos.InstructionDetailView(toView(instruction, now), samples, reconciliations, adjustments);
    }
    /** 锁定指令行（FOR UPDATE），不存在时 404。 */
    private Instruction lockInstruction(long instructionId) {
        return instructionRepository.findByIdForUpdate(instructionId)
                .orElseThrow(() -> ApiException.notFound("指令不存在: " + instructionId));
    }

    /** 刻度是否 15 分钟对齐。 */
    private static boolean isAligned(Instant instant) {
        return instant.getEpochSecond() % TICK_SECONDS == 0 && instant.getNano() == 0;
    }

    /** 窗口内 4 个刻度起点。 */
    private static List<Instant> windowTicks(Instant windowStart) {
        List<Instant> ticks = new ArrayList<>(WINDOW_TICKS);
        for (int i = 0; i < WINDOW_TICKS; i++) {
            ticks.add(windowStart.plusSeconds(TICK_SECONDS * i));
        }
        return ticks;
    }

    /** 为窗口全部刻度构造相同原因的问题列表。 */
    private static List<ApiDtos.ProblemTick> problems(List<Instant> ticks, String reason, String detail) {
        return ticks.stream().map(t -> new ApiDtos.ProblemTick(t, reason, detail)).toList();
    }

    private ApiDtos.InstructionView toView(Instruction instruction, Instant now) {
        return new ApiDtos.InstructionView(instruction.id(), instruction.instructionNo(), instruction.unitId(),
                instruction.startUtc(), instruction.endUtc(), instruction.maxPowerKw(),
                instruction.effectiveStatus(now).name(), instruction.version());
    }

    private ApiDtos.ReconciliationView toView(Reconciliation reconciliation) {
        List<ApiDtos.TickView> ticks = reconciliationRepository.findTicks(reconciliation.id()).stream()
                .map(t -> new ApiDtos.TickView(t.tickStart(), t.avgPowerKw(), t.sampleVersion(), t.energyKwh()))
                .toList();
        return new ApiDtos.ReconciliationView(reconciliation.id(), reconciliation.instructionId(),
                reconciliation.windowStart(), reconciliation.instructionVersion(), reconciliation.totalEnergyKwh(), ticks);
    }

    /**
     * 幂等执行：先查/插幂等记录，命中同参则重放首次响应，异参抛 409；
     * 未命中则在同一事务内执行业务并回填响应，保证记录与业务写入同生共死。
     */
    private <T> T executeIdempotent(String operation, String requestId, String fingerprint, Class<T> type,
                                    Supplier<T> action) {
        var existing = idempotencyRepository.find(operation, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            idempotencyRepository.insertPlaceholder(operation, requestId, fingerprint, clock.instant());
        } catch (DuplicateKeyException e) {
            var committed = idempotencyRepository.find(operation, requestId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "幂等记录竞争异常，请重试"));
            return replay(committed, fingerprint, type);
        }
        T result = action.get();
        try {
            idempotencyRepository.complete(operation, requestId, 200, objectMapper.writeValueAsString(result));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        return result;
    }

    /** 重放已固化的幂等响应；参数指纹不一致时抛 409。 */
    private <T> T replay(IdempotencyRepository.IdempotencyRecord record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已使用且参数不一致");
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /** 参数指纹：关键参数拼接后的 SHA-256 十六进制。 */
    private static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof BigDecimal decimal) {
                canonical.append(decimal.stripTrailingZeros().toPlainString());
            } else {
                canonical.append(part);
            }
            canonical.append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}