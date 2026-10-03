package com.example.starter.blackstart;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 黑启动业务：计划创建、步骤确认（幂等、读数校验、并发互斥）、终止与查询。
 */
@Service
public class BlackStartService {

    private final BlackStartRepository repository;
    private final Clock clock;

    public BlackStartService(BlackStartRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 创建新计划，从第一步开始。 */
    @Transactional
    public PlanView createPlan() {
        BlackStartPlan plan = new BlackStartPlan(0L, BlackStartPlan.STATUS_IN_PROGRESS, 1,
                clock.instant(), null, null, null);
        long id = repository.insertPlan(plan);
        return toView(repository.findPlan(id).orElseThrow(), List.of(), Set.of());
    }

    /**
     * 确认步骤。同 requestId 同参重放返回首个结果，异参抛 409；
     * 读数越界、设备状态错误或跳步记录 REJECTED，不推进步骤号。
     */
    @Transactional
    public ConfirmStepResponse confirmStep(long planId, int stepNo, ConfirmStepRequest request) {
        BlackStartPlan plan = repository.findPlan(planId)
                .orElseThrow(() -> BlackStartException.planNotFound(planId));

        Optional<StepAttempt> existing = repository.findAttemptByRequestId(request.requestId());
        if (existing.isPresent()) {
            StepAttempt attempt = existing.get();
            if (sameParams(attempt, planId, stepNo, request)) {
                return toResponse(attempt, plan);
            }
            throw BlackStartException.conflict(
                    "requestId 已使用且参数不一致: " + request.requestId());
        }

        if (!BlackStartPlan.STATUS_IN_PROGRESS.equals(plan.status())) {
            throw BlackStartException.conflict("计划已终态（" + plan.status() + "），记录只读");
        }

        String rejectReason = validate(plan, stepNo, request);
        if (rejectReason != null) {
            StepAttempt rejected = insertAttempt(planId, stepNo, request,
                    StepAttempt.OUTCOME_REJECTED, rejectReason);
            return toResponse(rejected, plan);
        }

        Instant now = clock.instant();
        try {
            StepAttempt success = insertAttempt(planId, stepNo, request,
                    StepAttempt.OUTCOME_SUCCESS, null);
            repository.insertStepSuccess(planId, stepNo, success.id(), now);
            int updated = repository.advancePlan(planId, stepNo, now);
            if (updated == 0) {
                throw BlackStartException.conflict("计划已终止或步骤已被并发推进");
            }
            BlackStartPlan after = repository.findPlan(planId).orElseThrow();
            return toResponse(success, after);
        } catch (DuplicateKeyException e) {
            throw BlackStartException.conflict("该步骤已被并发确认成功: 第 " + stepNo + " 步");
        }
    }

    /** 终止计划；已终止时幂等返回当前状态，已启动时抛 409。 */
    @Transactional
    public PlanView terminate(long planId, TerminateRequest request) {
        BlackStartPlan plan = repository.findPlan(planId)
                .orElseThrow(() -> BlackStartException.planNotFound(planId));
        if (BlackStartPlan.STATUS_TERMINATED.equals(plan.status())) {
            return getPlan(planId);
        }
        if (!BlackStartPlan.STATUS_IN_PROGRESS.equals(plan.status())) {
            throw BlackStartException.conflict("计划已启动，不能终止");
        }
        int updated = repository.terminatePlan(planId, request.reason(), clock.instant());
        if (updated == 0) {
            BlackStartPlan current = repository.findPlan(planId).orElseThrow();
            if (BlackStartPlan.STATUS_TERMINATED.equals(current.status())) {
                return getPlan(planId);
            }
            throw BlackStartException.conflict("计划已启动，不能终止");
        }
        return getPlan(planId);
    }

    /** 查询计划当前状态与全部步骤记录。 */
    @Transactional(readOnly = true)
    public PlanView getPlan(long planId) {
        BlackStartPlan plan = repository.findPlan(planId)
                .orElseThrow(() -> BlackStartException.planNotFound(planId));
        List<StepAttempt> attempts = repository.findAttemptsByPlan(planId);
        Set<Long> succeededIds = new HashSet<>(repository.findSucceededAttemptIds(planId));
        return toView(plan, attempts, succeededIds);
    }

    private StepAttempt insertAttempt(long planId, int stepNo, ConfirmStepRequest request,
            String outcome, String rejectReason) {
        StepAttempt attempt = new StepAttempt(0L, planId, stepNo, request.requestId(), outcome,
                request.deviceStatus(), request.voltageV(), request.frequencyHz(),
                request.powerKw(), rejectReason, clock.instant());
        long id = repository.insertAttempt(attempt);
        return repository.findAttemptByRequestId(request.requestId()).orElseThrow();
    }

    private String validate(BlackStartPlan plan, int stepNo, ConfirmStepRequest request) {
        if (stepNo < 1 || stepNo > BlackStartPlan.TOTAL_STEPS) {
            return "非法步骤号: " + stepNo;
        }
        if (stepNo != plan.currentStep()) {
            return "跳步被拒绝：当前应执行第 " + plan.currentStep() + " 步";
        }
        if (!StepAttempt.DEVICE_STATUS_NORMAL.equals(request.deviceStatus())) {
            return "设备状态错误: " + request.deviceStatus() + "，须为 NORMAL";
        }
        if (request.voltageV() < BlackStartPlan.MIN_VOLTAGE_V
                || request.voltageV() > BlackStartPlan.MAX_VOLTAGE_V) {
            return "电压 " + request.voltageV() + " V 越界，须在 380~420 V 内";
        }
        if (request.frequencyHz() < BlackStartPlan.MIN_FREQUENCY_HZ
                || request.frequencyHz() > BlackStartPlan.MAX_FREQUENCY_HZ) {
            return "频率 " + request.frequencyHz() + " Hz 越界，须在 49.5~50.5 Hz 内";
        }
        if (stepNo == BlackStartPlan.TOTAL_STEPS) {
            if (request.powerKw() == null) {
                return "第三步须提供负载接入功率";
            }
            if (request.powerKw() < 0 || request.powerKw() > BlackStartPlan.MAX_LOAD_POWER_KW) {
                return "负载功率 " + request.powerKw() + " kW 超过计划上限 200 kW";
            }
        }
        return null;
    }

    private boolean sameParams(StepAttempt attempt, long planId, int stepNo,
            ConfirmStepRequest request) {
        return attempt.planId() == planId
                && attempt.stepNo() == stepNo
                && Objects.equals(attempt.deviceStatus(), request.deviceStatus())
                && Objects.equals(attempt.voltageV(), request.voltageV())
                && Objects.equals(attempt.frequencyHz(), request.frequencyHz())
                && Objects.equals(attempt.powerKw(), request.powerKw());
    }

    private ConfirmStepResponse toResponse(StepAttempt attempt, BlackStartPlan plan) {
        return new ConfirmStepResponse(attempt.planId(), attempt.stepNo(), attempt.requestId(),
                attempt.outcome(), attempt.rejectReason(), plan.status(), plan.currentStep(),
                attempt.recordedAt());
    }

    private PlanView toView(BlackStartPlan plan, List<StepAttempt> attempts,
            Set<Long> succeededIds) {
        List<PlanView.StepView> steps = new ArrayList<>();
        for (int stepNo = 1; stepNo <= BlackStartPlan.TOTAL_STEPS; stepNo++) {
            List<PlanView.AttemptView> stepAttempts = new ArrayList<>();
            boolean succeeded = false;
            Instant succeededAt = null;
            for (StepAttempt attempt : attempts) {
                if (attempt.stepNo() != stepNo) {
                    continue;
                }
                stepAttempts.add(new PlanView.AttemptView(attempt.requestId(), attempt.outcome(),
                        attempt.deviceStatus(), attempt.voltageV(), attempt.frequencyHz(),
                        attempt.powerKw(), attempt.rejectReason(), attempt.recordedAt()));
                if (succeededIds.contains(attempt.id())) {
                    succeeded = true;
                    succeededAt = attempt.recordedAt();
                }
            }
            steps.add(new PlanView.StepView(stepNo, succeeded, succeededAt, stepAttempts));
        }
        return new PlanView(plan.id(), plan.status(), plan.currentStep(), plan.createdAt(),
                plan.completedAt(), plan.terminatedAt(), plan.terminationReason(), steps);
    }
}