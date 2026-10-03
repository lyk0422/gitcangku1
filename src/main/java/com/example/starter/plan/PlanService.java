package com.example.starter.plan;

import com.example.starter.plan.PlanDtos.CommandView;
import com.example.starter.plan.PlanDtos.ConfirmRequest;
import com.example.starter.plan.PlanDtos.ConfirmationView;
import com.example.starter.plan.PlanDtos.CreatePlanRequest;
import com.example.starter.plan.PlanDtos.ExecuteRequest;
import com.example.starter.plan.PlanDtos.IssueRequest;
import com.example.starter.plan.PlanDtos.PersonEntryRequest;
import com.example.starter.plan.PlanDtos.PersonEntryView;
import com.example.starter.plan.PlanDtos.PlanView;
import com.example.starter.plan.PlanDtos.ReportView;
import com.example.starter.plan.PlanDtos.RoundView;
import com.example.starter.plan.PlanRepository.CommandRow;
import com.example.starter.plan.PlanRepository.ConfirmationRow;
import com.example.starter.plan.PlanRepository.PersonEntryRow;
import com.example.starter.plan.PlanRepository.PlanRow;
import com.example.starter.plan.PlanRepository.ReportRow;
import com.example.starter.plan.PlanRepository.RoundRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清场业务服务：计划、检查点确认、人员进入、指令签发与执行回报。
 * 所有写操作在事务内对计划行加锁（SELECT ... FOR UPDATE），
 * 人员进入与执行回报等竞争操作按提交顺序串行裁决，不会既失效又成功。
 */
@Service
public class PlanService {

    /** 检查点确认的有效窗口：签发时确认时刻距当前不得超过 15 分钟。 */
    static final Duration CONFIRMATION_WINDOW = Duration.ofMinutes(15);

    /** 指令有效期：签发后 5 分钟。 */
    static final Duration COMMAND_VALIDITY = Duration.ofMinutes(5);

    private final PlanRepository repository;
    private final Clock clock;

    public PlanService(PlanRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public PlanView createPlan(CreatePlanRequest request) {
        if (repository.findPlanByCode(request.planCode()).isPresent()) {
            throw ApiException.conflict("计划编号已存在: " + request.planCode());
        }
        LinkedHashSet<String> checkpoints = new LinkedHashSet<>(request.checkpoints());
        if (checkpoints.size() != request.checkpoints().size()) {
            throw ApiException.badRequest("检查点清单存在重复编号");
        }
        long planId = repository.insertPlan(request.planCode(), request.areaBoundary(), Instant.now(clock));
        int seq = 1;
        for (String code : checkpoints) {
            repository.insertCheckpoint(planId, code, seq++);
        }
        return getPlan(planId);
    }

    @Transactional
    public ConfirmationView confirmCheckpoint(long planId, String checkpointCode, ConfirmRequest request) {
        lockActivePlan(planId);
        List<String> checkpoints = repository.findCheckpointCodes(planId);
        if (!checkpoints.contains(checkpointCode)) {
            throw ApiException.notFound("检查点不存在: " + checkpointCode);
        }
        if (request.headcount() != 0) {
            throw ApiException.badRequest("检查点人数必须为零才能确认清场");
        }
        Instant now = Instant.now(clock);
        Instant confirmedAt = request.confirmedAt() != null ? request.confirmedAt() : now;
        if (confirmedAt.isAfter(now)) {
            throw ApiException.badRequest("确认时刻不能晚于当前时刻");
        }
        RoundRow round = repository.findLatestRound(planId)
                .filter(r -> "OPEN".equals(r.status()))
                .orElseGet(() -> openNextRound(planId, now));
        repository.findConfirmation(round.id(), checkpointCode)
                .ifPresentOrElse(
                        existing -> repository.updateConfirmation(
                                existing.id(), request.confirmer(), request.headcount(), confirmedAt),
                        () -> repository.insertConfirmation(
                                round.id(), checkpointCode, request.confirmer(), request.headcount(), confirmedAt));
        if (repository.findConfirmations(round.id()).size() == checkpoints.size()) {
            repository.updateRoundStatus(round.id(), "COMPLETED", null);
            repository.updatePlanOccupied(planId, false);
        }
        return new ConfirmationView(round.roundNo(), checkpointCode, request.confirmer(),
                request.headcount(), confirmedAt);
    }

    @Transactional
    public PersonEntryView recordPersonEntry(long planId, PersonEntryRequest request) {
        lockActivePlan(planId);
        Instant now = Instant.now(clock);
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : now;
        if (occurredAt.isAfter(now)) {
            throw ApiException.badRequest("人员进入时刻不能晚于当前时刻");
        }
        long eventId = repository.insertPersonEntry(planId, request.reporter(), occurredAt, now);
        repository.updatePlanOccupied(planId, true);
        repository.invalidateIssuedCommands(planId);
        repository.findLatestRound(planId)
                .filter(r -> !"CLOSED".equals(r.status()))
                .ifPresent(r -> repository.updateRoundStatus(r.id(), "CLOSED", now));
        return new PersonEntryView(eventId, request.reporter(), occurredAt, now);
    }
    @Transactional
    public CommandView issueCommand(long planId, IssueRequest request) {
        PlanRow plan = lockActivePlan(planId);
        Instant now = Instant.now(clock);
        String payloadHash = hash("ISSUE", String.valueOf(planId), request.issuedBy());
        var replay = repository.findCommandByRequestId(request.requestId());
        if (replay.isPresent()) {
            return replayCommand(replay.get(), payloadHash);
        }
        if (plan.occupied()) {
            throw ApiException.conflict("区域标记有人员进入，禁止签发指令");
        }
        RoundRow round = repository.findLatestRound(planId)
                .filter(r -> "COMPLETED".equals(r.status()))
                .orElseThrow(() -> ApiException.conflict("检查点确认不完整，无法签发指令"));
        Instant cutoff = now.minus(CONFIRMATION_WINDOW);
        for (ConfirmationRow c : repository.findConfirmations(round.id())) {
            if (c.confirmedAt().isBefore(cutoff)) {
                throw ApiException.conflict("检查点 " + c.checkpointCode() + " 的确认已超过 15 分钟");
            }
        }
        if (repository.findValidCommand(planId, now).isPresent()) {
            throw ApiException.conflict("已存在未过期的有效指令，禁止重复签发");
        }
        try {
            long commandId = repository.insertCommand(planId, round.id(), request.requestId(),
                    payloadHash, request.issuedBy(), now, now.plus(COMMAND_VALIDITY));
            return toView(repository.findCommandById(commandId).orElseThrow());
        } catch (DuplicateKeyException e) {
            return replayCommand(repository.findCommandByRequestId(request.requestId()).orElseThrow(),
                    payloadHash);
        }
    }

    @Transactional
    public ReportView reportExecution(long planId, long commandId, ExecuteRequest request) {
        repository.lockPlanById(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
        Instant now = Instant.now(clock);
        String payloadHash = hash("EXECUTE", String.valueOf(planId), String.valueOf(commandId), request.executor());
        var replay = repository.findReportByRequestId(request.requestId());
        if (replay.isPresent()) {
            return replayReport(replay.get(), payloadHash);
        }
        CommandRow command = repository.findCommandById(commandId)
                .filter(c -> c.planId() == planId)
                .orElseThrow(() -> ApiException.notFound("指令不存在: " + commandId));
        if (!"ISSUED".equals(command.status())) {
            throw ApiException.conflict("指令状态为 " + command.status() + "，不可执行");
        }
        if (!command.expiresAt().isAfter(now)) {
            throw ApiException.conflict("指令已过期，不可执行");
        }
        long reportId;
        try {
            reportId = repository.insertReport(commandId, request.requestId(), payloadHash,
                    request.executor(), "SUCCESS", now);
        } catch (DuplicateKeyException e) {
            return replayReport(repository.findReportByRequestId(request.requestId()).orElseThrow(),
                    payloadHash);
        }
        repository.markCommandExecuted(commandId, now);
        repository.updatePlanStatus(planId, "COMPLETED");
        return toView(repository.findReportByRequestId(request.requestId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PlanView getPlan(long planId) {
        PlanRow plan = repository.findPlanById(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
        List<RoundView> rounds = repository.findRounds(planId).stream()
                .map(r -> new RoundView(r.roundNo(), r.status(), r.openedAt(), r.closedAt(),
                        repository.findConfirmations(r.id()).stream()
                                .map(c -> new ConfirmationView(r.roundNo(), c.checkpointCode(),
                                        c.confirmer(), c.headcount(), c.confirmedAt()))
                                .toList()))
                .toList();
        return new PlanView(plan.id(), plan.planCode(), plan.areaBoundary(), plan.status(),
                plan.occupied(), plan.createdAt(), repository.findCheckpointCodes(planId), rounds,
                repository.findCommands(planId).stream().map(this::toView).toList(),
                repository.findReports(planId).stream().map(this::toView).toList(),
                repository.findPersonEntries(planId).stream().map(this::toView).toList());
    }

    private PlanRow lockActivePlan(long planId) {
        PlanRow plan = repository.lockPlanById(planId)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + planId));
        if (!"ACTIVE".equals(plan.status())) {
            throw ApiException.conflict("计划已结束，禁止变更: " + planId);
        }
        return plan;
    }

    private RoundRow openNextRound(long planId, Instant now) {
        long roundNo = repository.findLatestRound(planId).map(r -> r.roundNo() + 1).orElse(1L);
        long roundId = repository.insertRound(planId, roundNo, now);
        return repository.findLatestRound(planId).filter(r -> r.id() == roundId).orElseThrow();
    }

    private CommandView replayCommand(CommandRow command, String payloadHash) {
        if (!command.payloadHash().equals(payloadHash)) {
            throw ApiException.conflict("requestId 已被使用且请求参数不一致");
        }
        return toView(command);
    }

    private ReportView replayReport(ReportRow report, String payloadHash) {
        if (!report.payloadHash().equals(payloadHash)) {
            throw ApiException.conflict("requestId 已被使用且请求参数不一致");
        }
        return toView(report);
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    private CommandView toView(CommandRow row) {
        return new CommandView(row.id(), row.requestId(), row.issuedBy(), row.issuedAt(),
                row.expiresAt(), row.status(), row.executedAt());
    }

    private ReportView toView(ReportRow row) {
        return new ReportView(row.id(), row.commandId(), row.requestId(), row.executor(),
                row.result(), row.reportedAt());
    }

    private PersonEntryView toView(PersonEntryRow row) {
        return new PersonEntryView(row.id(), row.reporter(), row.occurredAt(), row.recordedAt());
    }
}