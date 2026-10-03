package com.example.starter.blackstart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 黑启动领域服务：计划创建、步骤确认、终止与查询。
 *
 * <p>并发与幂等约定：步骤确认按 requestId 幂等（同参重放返回原结果，异参 409）；
 * 计划行的 SELECT ... FOR UPDATE 与条件 UPDATE 保证同一步骤只提交一个成功读数，
 * 终止与步骤执行竞争时只允许一个终态；事务回滚时 requestId 不残留，可用原键重试。</p>
 */
@Service
public class BlackStartService {

    /** 固定步骤数：1 电池母线带电；2 逆变器同步；3 关键负载接入。 */
    public static final int STEP_COUNT = 3;
    /** 母线电压下限（V）。 */
    public static final BigDecimal MIN_VOLTAGE = new BigDecimal("380");
    /** 母线电压上限（V）。 */
    public static final BigDecimal MAX_VOLTAGE = new BigDecimal("420");
    /** 频率下限（Hz）。 */
    public static final BigDecimal MIN_FREQUENCY = new BigDecimal("49.5");
    /** 频率上限（Hz）。 */
    public static final BigDecimal MAX_FREQUENCY = new BigDecimal("50.5");
    /** 第三步负载接入功率计划上限（kW）。 */
    public static final BigDecimal MAX_POWER_KW = new BigDecimal("200");
    /** 可接受的设备状态。 */
    public static final String DEVICE_STATUS_OK = "NORMAL";

    private final PlanRepository planRepository;
    private final AttemptRepository attemptRepository;
    private final Clock clock;

    public BlackStartService(PlanRepository planRepository, AttemptRepository attemptRepository, Clock clock) {
        this.planRepository = planRepository;
        this.attemptRepository = attemptRepository;
        this.clock = clock;
    }

    /**
     * 创建新的黑启动计划，初始状态为执行中、当前步骤为 1。
     */
    @Transactional
    public PlanView createPlan(String name) {
        String planName = (name == null || name.isBlank()) ? "黑启动计划" : name;
        long id = planRepository.insert(planName, clock.instant());
        return getPlan(id);
    }

    /**
     * 查询计划当前状态与全部确认记录。
     */
    @Transactional(readOnly = true)
    public PlanView getPlan(long planId) {
        BlackStartPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + planId));
        List<PlanView.StepRecord> steps = attemptRepository.findByPlanId(planId).stream()
                .map(BlackStartService::toStepRecord)
                .toList();
        return new PlanView(plan.id(), plan.name(), plan.status(), plan.currentStep(),
                plan.createdAt(), plan.completedAt(), plan.terminatedAt(), plan.terminationReason(), steps);
    }

    /**
     * 确认指定步骤。返回的 ConfirmResponse 搭配 HTTP 状态：成功 200，拒绝 422，重放保持原状态。
     */
    @Transactional
    public ConfirmOutcome confirmStep(long planId, int stepNo, ConfirmStepRequest request) {
        BlackStartPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + planId));
        String requestHash = requestHash(stepNo, request);

        Optional<StepAttempt> existing = attemptRepository.findByPlanIdAndRequestId(planId, request.requestId());
        if (existing.isPresent()) {
            StepAttempt attempt = existing.get();
            if (attempt.stepNo() != stepNo || !attempt.requestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                        "requestId 已使用且参数不一致: " + request.requestId());
            }
            return new ConfirmOutcome(toConfirmResponse(attempt, plan), statusOf(attempt));
        }
        if (plan.isTerminated()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_TERMINATED", "计划已终止，记录只读，重新启动须创建新计划");
        }
        if (plan.isStarted()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_ALREADY_STARTED", "计划已启动，不允许再执行步骤");
        }
        if (stepNo < 1 || stepNo > STEP_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STEP_OUT_OF_RANGE", "步骤号须在 1~" + STEP_COUNT + " 之间");
        }
        if (stepNo < plan.currentStep()) {
            throw new ApiException(HttpStatus.CONFLICT, "STEP_ALREADY_COMPLETED",
                    "第 " + stepNo + " 步已完成，当前应执行第 " + plan.currentStep() + " 步");
        }

        Instant now = clock.instant();
        if (stepNo > plan.currentStep()) {
            StepAttempt rejected = insertAttempt(planId, stepNo, request, requestHash,
                    StepAttempt.OUTCOME_REJECTED,
                    "跳步：当前应执行第 " + plan.currentStep() + " 步", now);
            return new ConfirmOutcome(toConfirmResponse(rejected, plan), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String violation = validateReadings(stepNo, request);
        if (violation != null) {
            StepAttempt rejected = insertAttempt(planId, stepNo, request, requestHash,
                    StepAttempt.OUTCOME_REJECTED, violation, now);
            return new ConfirmOutcome(toConfirmResponse(rejected, plan), HttpStatus.UNPROCESSABLE_ENTITY);
        }

        boolean lastStep = stepNo == STEP_COUNT;
        int updated = planRepository.advanceStep(planId, stepNo, lastStep, now);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "计划状态已变化，事务已回滚，请使用原 requestId 重试");
        }
        StepAttempt success = insertAttempt(planId, stepNo, request, requestHash,
                StepAttempt.OUTCOME_SUCCESS, null, now);
        BlackStartPlan refreshed = planRepository.findById(planId).orElseThrow();
        return new ConfirmOutcome(toConfirmResponse(success, refreshed), HttpStatus.OK);
    }

    /**
     * 终止计划。已终止时幂等返回当前状态；已启动时返回 409。
     */
    @Transactional
    public PlanView terminatePlan(long planId, String reason) {
        BlackStartPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + planId));
        if (plan.isTerminated()) {
            return getPlan(planId);
        }
        if (plan.isStarted()) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_ALREADY_STARTED", "计划已启动，不能终止");
        }
        int updated = planRepository.terminate(planId, clock.instant(), reason);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "计划状态已变化，事务已回滚，请重试终止");
        }
        return getPlan(planId);
    }

    /**
     * 校验读数与设备状态，返回拒绝原因；全部合格返回 null。
     */
    private String validateReadings(int stepNo, ConfirmStepRequest request) {
        if (!DEVICE_STATUS_OK.equals(request.deviceStatus())) {
            return "设备状态错误: " + request.deviceStatus() + "，期望 " + DEVICE_STATUS_OK;
        }
        if (request.voltage().compareTo(MIN_VOLTAGE) < 0 || request.voltage().compareTo(MAX_VOLTAGE) > 0) {
            return "母线电压越界: 读数 " + request.voltage() + " V，允许范围 380~420 V";
        }
        if (request.frequency().compareTo(MIN_FREQUENCY) < 0
                || request.frequency().compareTo(MAX_FREQUENCY) > 0) {
            return "频率越界: 读数 " + request.frequency() + " Hz，允许范围 49.5~50.5 Hz";
        }
        if (stepNo == STEP_COUNT) {
            if (request.powerKw() == null) {
                return "第三步缺少负载接入功率读数";
            }
            if (request.powerKw().compareTo(BigDecimal.ZERO) < 0
                    || request.powerKw().compareTo(MAX_POWER_KW) > 0) {
                return "负载接入功率超限: 读数 " + request.powerKw() + " kW，计划上限 200 kW";
            }
        }
        return null;
    }

    private StepAttempt insertAttempt(long planId, int stepNo, ConfirmStepRequest request,
            String requestHash, String outcome, String rejectReason, Instant now) {
        return attemptRepository.insert(new StepAttempt(0L, planId, stepNo, request.requestId(), requestHash,
                outcome, request.voltage(), request.frequency(), request.powerKw(),
                request.deviceStatus(), rejectReason, now));
    }

    private static String requestHash(int stepNo, ConfirmStepRequest request) {
        String canonical = stepNo + "|" + number(request.voltage()) + "|" + number(request.frequency())
                + "|" + number(request.powerKw()) + "|" + request.deviceStatus();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String number(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static HttpStatus statusOf(StepAttempt attempt) {
        return StepAttempt.OUTCOME_SUCCESS.equals(attempt.outcome())
                ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
    }

    private static ConfirmResponse toConfirmResponse(StepAttempt attempt, BlackStartPlan plan) {
        return new ConfirmResponse(attempt.planId(), attempt.stepNo(), attempt.requestId(), attempt.outcome(),
                plan.status(), plan.currentStep(), attempt.rejectReason(),
                attempt.voltage(), attempt.frequency(), attempt.powerKw(), attempt.recordedAt());
    }

    private static PlanView.StepRecord toStepRecord(StepAttempt attempt) {
        return new PlanView.StepRecord(attempt.stepNo(), attempt.requestId(), attempt.outcome(),
                attempt.voltage(), attempt.frequency(), attempt.powerKw(), attempt.deviceStatus(),
                attempt.rejectReason(), attempt.recordedAt());
    }

    /**
     * 步骤确认结果：响应体与对应的 HTTP 状态。
     *
     * @param response 响应体
     * @param httpStatus HTTP 状态：成功 200，拒绝 422，重放保持原状态
     */
    public record ConfirmOutcome(ConfirmResponse response, HttpStatus httpStatus) {
    }
}