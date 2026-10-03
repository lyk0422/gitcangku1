package com.example.starter.plan;

import com.example.starter.error.ApiException;
import com.example.starter.plan.PlanRequests.CheckpointInput;
import com.example.starter.plan.PlanViews.CheckpointState;
import com.example.starter.plan.PlanViews.CommandView;
import com.example.starter.plan.PlanViews.ConfirmationView;
import com.example.starter.plan.PlanViews.EntryView;
import com.example.starter.plan.PlanViews.ExecutionOutcome;
import com.example.starter.plan.PlanViews.ExecutionView;
import com.example.starter.plan.PlanViews.IssueOutcome;
import com.example.starter.plan.PlanViews.PlanDetail;
import com.example.starter.plan.PlanViews.PlanStateView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清场爆破核心业务：计划、检查点确认、人员进入、指令签发与执行回报。
 *
 * <p>规则：全部检查点在最近 15 分钟内确认且区域未标记有人进入，才能签发 5 分钟
 * 有效的合成爆破指令；人员重新进入使所有未执行指令失效；执行回报只能引用未过期
 * 且未撤销的指令，成功后计划结束。签发与执行按 requestId 幂等，同参重放、异参 409，
 * 失败不占幂等键。并发通过对计划行加写锁（FOR UPDATE）按已提交顺序裁决。
 */
@Service
public class PlanService {

    /** 检查点确认有效窗口（分钟）。 */
    static final long CONFIRMATION_WINDOW_MINUTES = 15;

    /** 指令有效时长（分钟）。 */
    static final long COMMAND_TTL_MINUTES = 5;

    private final PlanRepository repository;
    private final Clock clock;

    public PlanService(PlanRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 创建计划：固定区域边界与检查点清单，初始轮次为 1。 */
    @Transactional
    public PlanStateView createPlan(String name, String boundary, List<CheckpointInput> checkpoints) {
        long planId = repository.insertPlan(name, boundary, Instant.now(clock));
        for (CheckpointInput cp : checkpoints) {
            repository.insertCheckpoint(planId, cp.code(), cp.description());
        }
        return new PlanStateView(planId, name, boundary, "OPEN", false, 1);
    }

    /** 记录检查点确认：人数必须为零，确认计入当前清场轮次。 */
    @Transactional
    public ConfirmationView confirm(long planId, String checkpointCode, String confirmedBy,
            int headcount) {
        Map<String, Object> plan = lockOpenPlan(planId);
        if (headcount != 0) {
            throw ApiException.unprocessable("HEADCOUNT_NOT_ZERO", "确认结果要求区域人数为零");
        }
        Map<String, Object> checkpoint = null;
        for (Map<String, Object> row : repository.findCheckpoints(planId)) {
            if (checkpointCode.equals(row.get("CODE"))) {
                checkpoint = row;
                break;
            }
        }
        if (checkpoint == null) {
            throw ApiException.notFound("CHECKPOINT_NOT_FOUND", "检查点不属于该计划: " + checkpointCode);
        }
        Instant now = Instant.now(clock);
        int round = asInt(plan.get("CURRENT_ROUND"));
        long id = repository.insertConfirmation(planId, asLong(checkpoint.get("ID")), round,
                confirmedBy, headcount, now);
        return new ConfirmationView(id, checkpointCode, round, confirmedBy, headcount, now);
    }

    /** 人员重新进入：未执行指令全部失效，轮次递增，旧确认保留但不可复用。 */
    @Transactional
    public EntryView recordEntry(long planId, String detectedBy) {
        Map<String, Object> plan = lockOpenPlan(planId);
        Instant now = Instant.now(clock);
        int round = asInt(plan.get("CURRENT_ROUND"));
        long entryId = repository.insertEntry(planId, round, detectedBy, now);
        repository.invalidateActiveCommands(planId);
        repository.updatePlanState(planId, true, round + 1);
        return new EntryView(entryId, planId, round + 1, true, now);
    }

    /** 重新清场：解除有人标记，之后须在当前轮次重新确认全部检查点。 */
    @Transactional
    public PlanStateView clearZone(long planId, String clearedBy) {
        Map<String, Object> plan = lockOpenPlan(planId);
        int round = asInt(plan.get("CURRENT_ROUND"));
        repository.updatePlanState(planId, false, round);
        return new PlanStateView(planId, asString(plan.get("NAME")), asString(plan.get("BOUNDARY")),
                asString(plan.get("STATUS")), false, round);
    }
    /** 签发合成爆破指令：同 requestId 同参重放、异参 409，校验失败不占幂等键。 */
    @Transactional
    public IssueOutcome issueCommand(long planId, String requestId, String issuedBy) {
        Instant now = Instant.now(clock);
        Map<String, Object> plan = lockOpenPlan(planId);
        String paramsHash = sha256("issue|" + issuedBy);
        Map<String, Object> existing = repository.findCommandByRequestId(planId, requestId);
        if (existing != null) {
            if (!paramsHash.equals(existing.get("PARAMS_HASH"))) {
                throw ApiException.conflict("IDEMPOTENT_CONFLICT",
                        "requestId 已使用且参数不同: " + requestId);
            }
            return new IssueOutcome(toCommandView(existing, now), true);
        }
        if (asBool(plan.get("OCCUPIED"))) {
            throw ApiException.unprocessable("ZONE_OCCUPIED", "区域已标记有人进入，须重新清场");
        }
        if (!repository.findLiveCommands(planId, now).isEmpty()) {
            throw ApiException.unprocessable("COMMAND_ALREADY_ACTIVE", "已存在未过期的有效指令");
        }
        List<Map<String, Object>> checkpoints = repository.findCheckpoints(planId);
        int round = asInt(plan.get("CURRENT_ROUND"));
        Map<Long, Instant> latestByCheckpoint = new LinkedHashMap<>();
        for (Map<String, Object> row : repository.findConfirmations(planId)) {
            if (asInt(row.get("ROUND_NO")) == round) {
                latestByCheckpoint.merge(asLong(row.get("CHECKPOINT_ID")),
                        asInstant(row.get("CONFIRMED_AT")), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Instant staleBefore = now.minus(CONFIRMATION_WINDOW_MINUTES, ChronoUnit.MINUTES);
        boolean anyStale = false;
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> cp : checkpoints) {
            Instant confirmedAt = latestByCheckpoint.get(asLong(cp.get("ID")));
            if (confirmedAt == null) {
                missing.add(asString(cp.get("CODE")));
            } else if (confirmedAt.isBefore(staleBefore)) {
                anyStale = true;
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("CHECKPOINTS_INCOMPLETE",
                    "以下检查点未在当前轮次确认: " + String.join(",", missing));
        }
        if (anyStale) {
            throw ApiException.unprocessable("CONFIRMATION_STALE",
                    "存在超过 " + CONFIRMATION_WINDOW_MINUTES + " 分钟的确认，须重新清场确认");
        }
        long commandId = repository.insertCommand(planId, round, requestId, paramsHash, issuedBy,
                now, now.plus(COMMAND_TTL_MINUTES, ChronoUnit.MINUTES));
        return new IssueOutcome(new CommandView(commandId, planId, round, requestId, now,
                now.plus(COMMAND_TTL_MINUTES, ChronoUnit.MINUTES), "ACTIVE"), false);
    }

    /** 执行回报：只能引用未过期且未撤销的指令，成功后计划结束；幂等规则同签发。 */
    @Transactional
    public ExecutionOutcome reportExecution(long commandId, String requestId,
            String reportedBy, String result) {
        Instant now = Instant.now(clock);
        Map<String, Object> command = repository.findCommand(commandId);
        if (command == null) {
            throw ApiException.notFound("COMMAND_NOT_FOUND", "指令不存在: " + commandId);
        }
        long planId = asLong(command.get("PLAN_ID"));
        Map<String, Object> plan = lockPlanOrThrow(planId);
        // 拿到计划行写锁后必须重读指令，避免使用加锁前的过期状态（如并发进入已将其置为失效）
        command = repository.findCommand(commandId);
        String paramsHash = sha256("exec|" + reportedBy + "|" + result);
        Map<String, Object> existing = repository.findReportByRequestId(planId, requestId);
        if (existing != null) {
            if (!paramsHash.equals(existing.get("PARAMS_HASH"))) {
                throw ApiException.conflict("IDEMPOTENT_CONFLICT",
                        "requestId 已使用且参数不同: " + requestId);
            }
            return new ExecutionOutcome(toExecutionView(existing, plan), true);
        }
        if (!"OPEN".equals(plan.get("STATUS"))) {
            throw ApiException.unprocessable("PLAN_CLOSED", "计划已结束，不能再次执行");
        }
        String status = asString(command.get("STATUS"));
        if ("INVALIDATED".equals(status)) {
            throw ApiException.unprocessable("COMMAND_INVALIDATED", "指令已因人员进入而失效");
        }
        if ("EXECUTED".equals(status)) {
            throw ApiException.unprocessable("COMMAND_ALREADY_EXECUTED", "指令已被执行");
        }
        if (!asInstant(command.get("EXPIRES_AT")).isAfter(now)) {
            throw ApiException.unprocessable("COMMAND_EXPIRED", "指令已过有效期");
        }
        if (!"SUCCESS".equals(result)) {
            throw ApiException.unprocessable("UNSUPPORTED_RESULT", "执行结果仅支持 SUCCESS");
        }
        long reportId = repository.insertReport(planId, commandId, requestId, paramsHash,
                reportedBy, result, now);
        repository.updateCommandStatus(commandId, "EXECUTED");
        repository.updatePlanStatus(planId, "COMPLETED");
        return new ExecutionOutcome(
                new ExecutionView(reportId, commandId, planId, result, now, "COMPLETED"), false);
    }
    /** 查询计划：检查轮次、各点确认时刻、指令有效期与最终状态。 */
    @Transactional(readOnly = true)
    public PlanDetail getPlan(long planId) {
        Map<String, Object> plan = repository.findPlan(planId);
        if (plan == null) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "计划不存在: " + planId);
        }
        Instant now = Instant.now(clock);
        Map<Long, List<ConfirmationView>> confirmationsByCheckpoint = new LinkedHashMap<>();
        for (Map<String, Object> row : repository.findConfirmations(planId)) {
            ConfirmationView view = new ConfirmationView(asLong(row.get("ID")),
                    asString(row.get("CHECKPOINT_CODE")), asInt(row.get("ROUND_NO")),
                    asString(row.get("CONFIRMED_BY")), asInt(row.get("HEADCOUNT")),
                    asInstant(row.get("CONFIRMED_AT")));
            confirmationsByCheckpoint.computeIfAbsent(asLong(row.get("CHECKPOINT_ID")),
                    k -> new ArrayList<>()).add(view);
        }
        List<CheckpointState> checkpoints = new ArrayList<>();
        for (Map<String, Object> cp : repository.findCheckpoints(planId)) {
            checkpoints.add(new CheckpointState(asString(cp.get("CODE")),
                    asString(cp.get("DESCRIPTION")),
                    confirmationsByCheckpoint.getOrDefault(asLong(cp.get("ID")), List.of())));
        }
        List<CommandView> commands = new ArrayList<>();
        for (Map<String, Object> row : repository.findCommands(planId)) {
            commands.add(toCommandView(row, now));
        }
        List<EntryView> entries = new ArrayList<>();
        for (Map<String, Object> row : repository.findEntries(planId)) {
            entries.add(new EntryView(asLong(row.get("ID")), planId,
                    asInt(row.get("ROUND_NO")) + 1, true, asInstant(row.get("DETECTED_AT"))));
        }
        List<ExecutionView> executions = new ArrayList<>();
        for (Map<String, Object> row : repository.findReports(planId)) {
            executions.add(toExecutionView(row, plan));
        }
        return new PlanDetail(planId, asString(plan.get("NAME")), asString(plan.get("BOUNDARY")),
                asString(plan.get("STATUS")), asBool(plan.get("OCCUPIED")),
                asInt(plan.get("CURRENT_ROUND")), checkpoints, commands, entries, executions);
    }

    private Map<String, Object> lockPlanOrThrow(long planId) {
        Map<String, Object> plan = repository.lockPlan(planId);
        if (plan == null) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "计划不存在: " + planId);
        }
        return plan;
    }

    private Map<String, Object> lockOpenPlan(long planId) {
        Map<String, Object> plan = lockPlanOrThrow(planId);
        if (!"OPEN".equals(plan.get("STATUS"))) {
            throw ApiException.unprocessable("PLAN_CLOSED", "计划已结束");
        }
        return plan;
    }

    private CommandView toCommandView(Map<String, Object> row, Instant now) {
        String status = asString(row.get("STATUS"));
        Instant expiresAt = asInstant(row.get("EXPIRES_AT"));
        if ("ACTIVE".equals(status) && !expiresAt.isAfter(now)) {
            status = "EXPIRED";
        }
        return new CommandView(asLong(row.get("ID")), asLong(row.get("PLAN_ID")),
                asInt(row.get("ROUND_NO")), asString(row.get("REQUEST_ID")),
                asInstant(row.get("ISSUED_AT")), expiresAt, status);
    }

    private ExecutionView toExecutionView(Map<String, Object> row, Map<String, Object> plan) {
        return new ExecutionView(asLong(row.get("ID")), asLong(row.get("COMMAND_ID")),
                asLong(row.get("PLAN_ID")), asString(row.get("RESULT")),
                asInstant(row.get("REPORTED_AT")), asString(plan.get("STATUS")));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }

    private static boolean asBool(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant asInstant(Object value) {
        return ((Timestamp) value).toInstant();
    }
}