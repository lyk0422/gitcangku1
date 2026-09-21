package com.example.starter.site;

import com.example.starter.site.SiteRepository.ApprovalRow;
import com.example.starter.site.SiteRepository.CommandRow;
import com.example.starter.site.SiteRepository.IsolationRow;
import com.example.starter.site.SiteRepository.PermitRow;
import com.example.starter.site.dto.ApiError;
import com.example.starter.site.dto.ApprovalView;
import com.example.starter.site.dto.CreateIsolationRequest;
import com.example.starter.site.dto.CreatePermitRequest;
import com.example.starter.site.dto.IsolationView;
import com.example.starter.site.dto.PermitView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 现场隔离与作业许可业务服务。
 *
 * <p>所有变更操作在单实例全局互斥锁内以事务执行，保证并发请求按事务提交顺序生效；
 * 每个变更操作携带 commandKey 做幂等：同键同参重放返回首次结果，同键不同参返回 409。
 */
@Service
public class SiteService {

    /**
     * 命令执行结果：HTTP 状态码与响应体 JSON，原样落库用于幂等重放。
     */
    public record CommandOutcome(int status, String body) {
    }

    private final SiteRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final ReentrantLock mutationLock = new ReentrantLock();

    public SiteService(SiteRepository repo, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    // ---------- 幂等命令框架 ----------

    /**
     * 在全局互斥锁与事务内执行变更命令。若 commandKey 已存在：参数指纹一致则重放首次结果，
     * 不一致则抛 409。业务校验失败（BusinessException）同样作为首次结果被记录并重放。
     */
    public CommandOutcome execute(String operation, String commandKey, Object params,
                                  Supplier<CommandOutcome> action) {
        String fingerprint = fingerprint(operation, params);
        mutationLock.lock();
        try {
            return tx.execute(status -> {
                var existing = repo.findCommand(commandKey);
                if (existing.isPresent()) {
                    CommandRow row = existing.get();
                    if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint)) {
                        throw BusinessException.conflict("COMMAND_KEY_CONFLICT",
                                "commandKey 已被不同参数的请求使用");
                    }
                    return new CommandOutcome(row.httpStatus(), row.responseBody());
                }
                CommandOutcome outcome;
                try {
                    outcome = action.get();
                } catch (BusinessException be) {
                    outcome = new CommandOutcome(be.status(),
                            toJson(new ApiError(be.code(), be.getMessage())));
                }
                repo.insertCommand(new CommandRow(commandKey, operation, fingerprint,
                        outcome.status(), outcome.body(), nowMillis()));
                return outcome;
            });
        } finally {
            mutationLock.unlock();
        }
    }

    private String fingerprint(String operation, Object params) {
        try {
            String canonical = operation + "\n" + objectMapper.writeValueAsString(params);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化命令参数", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化响应体", e);
        }
    }

    private static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    // ---------- 隔离记录 ----------

    public CommandOutcome createIsolation(CreateIsolationRequest req) {
        return execute("CREATE_ISOLATION", req.commandKey(), req, () -> {
            if (!req.plannedStartUtc().isBefore(req.plannedEndUtc())) {
                throw BusinessException.badRequest("INVALID_TIME_RANGE", "计划开始必须早于计划结束");
            }
            if (repo.findIsolation(req.isolationKey()).isPresent()) {
                throw BusinessException.conflict("ISOLATION_KEY_EXISTS", "隔离记录键已存在");
            }
            long start = req.plannedStartUtc().toEpochMilli();
            long end = req.plannedEndUtc().toEpochMilli();
            if (!repo.findInstalledOverlapping(req.deviceId(), start, end).isEmpty()) {
                throw BusinessException.conflict("ISOLATION_OVERLAP", "同一设备已存在区间重叠的已安装隔离");
            }
            IsolationRow row = new IsolationRow(req.isolationKey(), req.deviceId(), start, end,
                    req.lockedBy(), IsolationStatus.INSTALLED, nowMillis(), null);
            repo.insertIsolation(row);
            return new CommandOutcome(201, toJson(toView(row)));
        });
    }

    public CommandOutcome removeIsolation(String isolationKey, String commandKey) {
        var params = new TreeMap<String, Object>();
        params.put("isolationKey", isolationKey);
        return execute("REMOVE_ISOLATION", commandKey, params, () -> {
            IsolationRow row = repo.findIsolation(isolationKey)
                    .orElseThrow(() -> BusinessException.notFound("ISOLATION_NOT_FOUND", "隔离记录不存在"));
            if (row.status() == IsolationStatus.REMOVED) {
                throw BusinessException.conflict("ISOLATION_ALREADY_REMOVED", "隔离记录已拆除");
            }
            if (repo.countEffectivePermitsReferencing(isolationKey) > 0) {
                throw BusinessException.conflict("ISOLATION_IN_USE", "隔离被生效许可引用，禁止拆除");
            }
            long removedAt = nowMillis();
            repo.markIsolationRemoved(isolationKey, removedAt);
            IsolationRow updated = new IsolationRow(row.isolationKey(), row.deviceId(),
                    row.plannedStartUtc(), row.plannedEndUtc(), row.lockedBy(),
                    IsolationStatus.REMOVED, row.createdAt(), removedAt);
            return new CommandOutcome(200, toJson(toView(updated)));
        });
    }

    public IsolationView getIsolation(String isolationKey) {
        return toView(repo.findIsolation(isolationKey)
                .orElseThrow(() -> BusinessException.notFound("ISOLATION_NOT_FOUND", "隔离记录不存在")));
    }

    public List<IsolationView> listIsolations(String deviceId, String status) {
        return repo.listIsolations(deviceId, parseIsolationStatus(status)).stream()
                .map(SiteService::toView).toList();
    }

    // ---------- 作业许可 ----------

    public CommandOutcome createPermit(CreatePermitRequest req) {
        return execute("CREATE_PERMIT", req.commandKey(), req, () -> {
            if (!req.workStartUtc().isBefore(req.workEndUtc())) {
                throw BusinessException.badRequest("INVALID_TIME_RANGE", "作业开始必须早于作业结束");
            }
            if (repo.findPermit(req.permitKey()).isPresent()) {
                throw BusinessException.conflict("PERMIT_KEY_EXISTS", "作业许可键已存在");
            }
            List<String> keys = req.isolationKeys().stream().distinct().toList();
            List<IsolationRow> isolations = repo.findIsolationsByKeys(keys);
            requireInstalledAndCovering(isolations, keys.size(),
                    req.workStartUtc().toEpochMilli(), req.workEndUtc().toEpochMilli());
            PermitRow row = new PermitRow(req.permitKey(), req.crewName(),
                    req.workStartUtc().toEpochMilli(), req.workEndUtc().toEpochMilli(),
                    req.applicant(), PermitStatus.PENDING, nowMillis(), null);
            repo.insertPermit(row);
            keys.forEach(k -> repo.insertPermitIsolation(req.permitKey(), k));
            return new CommandOutcome(201, toJson(toView(row)));
        });
    }

    public CommandOutcome approvePermit(String permitKey, String actor, String commandKey) {
        var params = new TreeMap<String, Object>();
        params.put("permitKey", permitKey);
        params.put("actor", actor);
        return execute("APPROVE_PERMIT", commandKey, params, () -> {
            PermitRow permit = repo.findPermit(permitKey)
                    .orElseThrow(() -> BusinessException.notFound("PERMIT_NOT_FOUND", "作业许可不存在"));
            if (permit.status() != PermitStatus.PENDING) {
                throw BusinessException.conflict("PERMIT_NOT_PENDING", "许可不处于待审批状态");
            }
            if (permit.applicant().equals(actor)) {
                throw BusinessException.unprocessable("APPROVER_IS_APPLICANT", "审核人不得为申请人");
            }
            List<ApprovalRow> approvals = repo.listApprovals(permitKey);
            if (approvals.stream().anyMatch(a -> a.approver().equals(actor))) {
                throw BusinessException.conflict("ALREADY_APPROVED", "该审核人已批准过此许可");
            }
            List<String> keys = repo.findPermitIsolationKeys(permitKey);
            List<IsolationRow> isolations = repo.findIsolationsByKeys(keys);
            requireInstalledAndCovering(isolations, keys.size(),
                    permit.workStartUtc(), permit.workEndUtc());
            for (IsolationRow iso : isolations) {
                if (!repo.findEffectivePermitsOccupying(iso.isolationKey(),
                        permit.workStartUtc(), permit.workEndUtc(), permitKey).isEmpty()) {
                    throw BusinessException.unprocessable("ISOLATION_OCCUPIED",
                            "隔离 " + iso.isolationKey() + " 已被另一张生效许可在重叠时段占用");
                }
            }
            int seq = approvals.size() + 1;
            repo.insertApproval(new ApprovalRow(permitKey, actor, seq, nowMillis()));
            if (seq >= 2) {
                repo.updatePermitStatus(permitKey, PermitStatus.EFFECTIVE, null);
            }
            return new CommandOutcome(200, toJson(toView(repo.findPermit(permitKey).orElseThrow())));
        });
    }

    public CommandOutcome closePermit(String permitKey, String actor, String commandKey) {
        var params = new TreeMap<String, Object>();
        params.put("permitKey", permitKey);
        params.put("actor", actor);
        return execute("CLOSE_PERMIT", commandKey, params, () -> {
            PermitRow permit = repo.findPermit(permitKey)
                    .orElseThrow(() -> BusinessException.notFound("PERMIT_NOT_FOUND", "作业许可不存在"));
            if (permit.status() != PermitStatus.EFFECTIVE) {
                throw BusinessException.conflict("PERMIT_NOT_EFFECTIVE", "许可不处于生效状态，不能关闭");
            }
            if (!permit.applicant().equals(actor)) {
                throw BusinessException.unprocessable("NOT_APPLICANT", "只有申请人可以关闭许可");
            }
            repo.updatePermitStatus(permitKey, PermitStatus.CLOSED, nowMillis());
            return new CommandOutcome(200, toJson(toView(repo.findPermit(permitKey).orElseThrow())));
        });
    }

    public PermitView getPermit(String permitKey) {
        return toView(repo.findPermit(permitKey)
                .orElseThrow(() -> BusinessException.notFound("PERMIT_NOT_FOUND", "作业许可不存在")));
    }

    public List<PermitView> listPermits(String status) {
        return repo.listPermits(parsePermitStatus(status)).stream().map(this::toView).toList();
    }

    public List<PermitView> listEffectivePermits() {
        return repo.listPermits(PermitStatus.EFFECTIVE).stream().map(this::toView).toList();
    }

    // ---------- 内部校验与映射 ----------

    /**
     * 校验引用隔离全部存在、全部已安装，且其区间并集完整覆盖 [startUtc, endUtc)。
     */
    private void requireInstalledAndCovering(List<IsolationRow> isolations, int expectedCount,
                                             long startUtc, long endUtc) {
        if (isolations.size() < expectedCount) {
            throw BusinessException.unprocessable("ISOLATION_NOT_INSTALLED", "引用的隔离记录不存在");
        }
        if (isolations.stream().anyMatch(i -> i.status() != IsolationStatus.INSTALLED)) {
            throw BusinessException.unprocessable("ISOLATION_NOT_INSTALLED", "引用的隔离记录未全部处于已安装状态");
        }
        if (!covers(isolations, startUtc, endUtc)) {
            throw BusinessException.unprocessable("WORK_INTERVAL_NOT_COVERED", "隔离区间未完整覆盖作业区间");
        }
    }

    /**
     * 区间并集覆盖判定：按起点排序后扫描，要求从 startUtc 到 endUtc 无空隙（左闭右开，端点相接合法）。
     */
    static boolean covers(List<IsolationRow> isolations, long startUtc, long endUtc) {
        long[][] intervals = isolations.stream()
                .map(i -> new long[]{i.plannedStartUtc(), i.plannedEndUtc()})
                .sorted((a, b) -> Long.compare(a[0], b[0]))
                .toArray(long[][]::new);
        long cursor = startUtc;
        for (long[] interval : intervals) {
            if (interval[1] <= cursor) {
                continue;
            }
            if (interval[0] > cursor) {
                return false;
            }
            cursor = Math.max(cursor, interval[1]);
            if (cursor >= endUtc) {
                return true;
            }
        }
        return cursor >= endUtc;
    }

    private static IsolationStatus parseIsolationStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return IsolationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("INVALID_STATUS", "非法隔离状态: " + status);
        }
    }

    private static PermitStatus parsePermitStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PermitStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("INVALID_STATUS", "非法许可状态: " + status);
        }
    }

    private static IsolationView toView(IsolationRow row) {
        return new IsolationView(row.isolationKey(), row.deviceId(),
                Instant.ofEpochMilli(row.plannedStartUtc()), Instant.ofEpochMilli(row.plannedEndUtc()),
                row.lockedBy(), row.status().name(), Instant.ofEpochMilli(row.createdAt()),
                row.removedAt() == null ? null : Instant.ofEpochMilli(row.removedAt()));
    }

    private PermitView toView(PermitRow row) {
        List<ApprovalView> approvals = repo.listApprovals(row.permitKey()).stream()
                .map(a -> new ApprovalView(a.approver(), a.seqNo(), Instant.ofEpochMilli(a.approvedAt())))
                .toList();
        return new PermitView(row.permitKey(), row.crewName(),
                Instant.ofEpochMilli(row.workStartUtc()), Instant.ofEpochMilli(row.workEndUtc()),
                row.applicant(), row.status().name(), repo.findPermitIsolationKeys(row.permitKey()),
                approvals, Instant.ofEpochMilli(row.createdAt()),
                row.closedAt() == null ? null : Instant.ofEpochMilli(row.closedAt()));
    }
}
