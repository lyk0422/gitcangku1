package com.example.starter.water;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.water.dto.AllocationView;
import com.example.starter.water.dto.CapacityView;
import com.example.starter.water.dto.CreateRestrictionCommand;
import com.example.starter.water.dto.CreateWindowCommand;
import com.example.starter.water.dto.KeyedCommand;
import com.example.starter.water.dto.RestrictionView;
import com.example.starter.water.dto.SubmitAllocationCommand;
import com.example.starter.water.dto.WindowHistoryView;
import com.example.starter.water.dto.WindowView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 灌区配水业务服务：窗口、申请、限供与幂等命令的事务编排。
 *
 * <p>幂等：每个写操作携带 commandKey，首次成功后落库参数指纹与响应快照；
 * 同键同参重放返回首次结果，同键改参返回 409。仅成功结果占用命令键，失败可重试。
 *
 * <p>并发：批准申请与创建/取消限供均先锁定窗口行（SELECT ... FOR UPDATE），
 * 按事务提交顺序生效，保证已批准总量不超过最终可用总量。
 */
@Service
public class WaterService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WaterRepository repository;
    private final TransactionTemplate transactions;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 创建供水窗口（幂等）。 */
    public WindowView createWindow(CreateWindowCommand command) {
        String windowKey = requireNonBlank(command == null ? null : command.windowKey(), "windowKey");
        String channelId = requireNonBlank(command.channelId(), "channelId");
        Instant start = parseInstant(command.startUtc(), "startUtc");
        Instant end = parseInstant(command.endUtc(), "endUtc");
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("endUtc 必须晚于 startUtc");
        }
        BigDecimal planned = parseVolume(command.plannedVolume(), "plannedVolume");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("windowKey", windowKey);
        params.put("channelId", channelId);
        params.put("startUtc", start.toString());
        params.put("endUtc", end.toString());
        params.put("plannedVolume", planned.toPlainString());
        return runCommand(command.commandKey(), "CREATE_WINDOW", params, WindowView.class, () -> {
            repository.lockChannel(channelId);
            if (repository.existsWindowKey(windowKey)) {
                throw ApiException.conflict("DUPLICATE_KEY", "windowKey 已存在: " + windowKey);
            }
            if (repository.existsOverlappingWindow(channelId, start.toEpochMilli(), end.toEpochMilli())) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同渠道已存在时间重叠的窗口");
            }
            long now = nowMs();
            long id = repository.insertWindow(windowKey, channelId, start.toEpochMilli(),
                    end.toEpochMilli(), planned, now);
            return toView(new WaterWindow(id, windowKey, channelId, start, end, planned,
                    Instant.ofEpochMilli(now)));
        });
    }

    /** 提交配水申请（幂等），初始状态 REQUESTED。 */
    public AllocationView submitAllocation(String actor, SubmitAllocationCommand command) {
        String applicant = requireNonBlank(actor, "X-Actor-Id");
        String allocationKey = requireNonBlank(command == null ? null : command.allocationKey(), "allocationKey");
        if (command.windowId() == null) {
            throw ApiException.badRequest("windowId 不能为空");
        }
        long windowId = command.windowId();
        String userId = requireNonBlank(command.userId(), "userId");
        BigDecimal volume = parseVolume(command.volume(), "volume");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actor", applicant);
        params.put("allocationKey", allocationKey);
        params.put("windowId", windowId);
        params.put("userId", userId);
        params.put("volume", volume.toPlainString());
        return runCommand(command.commandKey(), "SUBMIT_ALLOCATION", params, AllocationView.class, () -> {
            WaterWindow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("窗口不存在: " + windowId);
            }
            if (repository.findAllocationByKey(allocationKey) != null) {
                throw ApiException.conflict("DUPLICATE_KEY", "allocationKey 已存在: " + allocationKey);
            }
            long now = nowMs();
            long id = repository.insertAllocation(allocationKey, windowId, userId, volume,
                    applicant, WaterAllocation.STATUS_REQUESTED, now);
            return toView(new WaterAllocation(id, allocationKey, windowId, userId, volume, applicant,
                    WaterAllocation.STATUS_REQUESTED, Instant.ofEpochMilli(now), Instant.ofEpochMilli(now)));
        });
    }

    /** 批准申请（幂等）；加入本申请后超过当前可用总量时返回 422。 */
    public AllocationView approveAllocation(String allocationKey, KeyedCommand command) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("allocationKey", allocationKey);
        return runCommand(command == null ? null : command.commandKey(), "APPROVE_ALLOCATION",
                params, AllocationView.class, () -> {
                    WaterAllocation allocation = lockWindowOfAllocation(allocationKey);
                    switch (allocation.status()) {
                        case WaterAllocation.STATUS_REQUESTED -> {
                            WaterWindow window = repository.lockWindowById(allocation.windowId());
                            BigDecimal effective = effectiveVolume(window);
                            BigDecimal approved = repository.sumApprovedVolume(window.id());
                            if (approved.add(allocation.volume()).compareTo(effective) > 0) {
                                throw ApiException.quotaExceeded(
                                        "批准后总量 " + fmt(approved.add(allocation.volume()))
                                                + " 超过当前可用总量 " + fmt(effective));
                            }
                            long now = nowMs();
                            repository.updateAllocationStatus(allocation.id(),
                                    WaterAllocation.STATUS_APPROVED, now);
                            return toView(withStatus(allocation, WaterAllocation.STATUS_APPROVED, now));
                        }
                        case WaterAllocation.STATUS_APPROVED ->
                            throw ApiException.conflict("STATE_CONFLICT", "申请已处于 APPROVED 状态");
                        default ->
                            throw ApiException.conflict("STATE_CONFLICT", "已取消申请不能再次批准");
                    }
                });
    }

    /** 取消申请（幂等）；仅申请人本人可取消 REQUESTED 或 APPROVED 申请，取消立即释放占用水量。 */
    public AllocationView cancelAllocation(String actor, String allocationKey, KeyedCommand command) {
        String applicant = requireNonBlank(actor, "X-Actor-Id");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actor", applicant);
        params.put("allocationKey", allocationKey);
        return runCommand(command == null ? null : command.commandKey(), "CANCEL_ALLOCATION",
                params, AllocationView.class, () -> {
                    WaterAllocation allocation = lockWindowOfAllocation(allocationKey);
                    if (!allocation.applicant().equals(applicant)) {
                        throw ApiException.conflict("ACTOR_MISMATCH", "只有申请人本人可取消该申请");
                    }
                    if (WaterAllocation.STATUS_CANCELLED.equals(allocation.status())) {
                        throw ApiException.conflict("STATE_CONFLICT", "申请已取消，取消不可恢复");
                    }
                    long now = nowMs();
                    repository.updateAllocationStatus(allocation.id(), WaterAllocation.STATUS_CANCELLED, now);
                    return toView(withStatus(allocation, WaterAllocation.STATUS_CANCELLED, now));
                });
    }

    /** 创建限供（幂等）；当前已批准总量超过拟定限供水量时返回 422。 */
    public RestrictionView createRestriction(long windowId, CreateRestrictionCommand command) {
        BigDecimal limit = parseVolume(command == null ? null : command.limitVolume(), "limitVolume");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("windowId", windowId);
        params.put("limitVolume", limit.toPlainString());
        return runCommand(command.commandKey(), "CREATE_RESTRICTION", params, RestrictionView.class, () -> {
            WaterWindow window = lockWindow(windowId);
            if (limit.compareTo(window.plannedVolume()) > 0) {
                throw ApiException.badRequest("limitVolume 不得超过计划水量 " + fmt(window.plannedVolume()));
            }
            if (repository.findActiveRestriction(windowId) != null) {
                throw ApiException.conflict("STATE_CONFLICT", "窗口已存在生效中的限供");
            }
            BigDecimal approved = repository.sumApprovedVolume(windowId);
            if (approved.compareTo(limit) > 0) {
                throw ApiException.quotaExceeded(
                        "当前已批准总量 " + fmt(approved) + " 超过拟定限供水量 " + fmt(limit));
            }
            long now = nowMs();
            long id = repository.insertRestriction(windowId, limit, now);
            return toView(new WaterRestriction(id, windowId, limit,
                    WaterRestriction.STATUS_ACTIVE, Instant.ofEpochMilli(now), null));
        });
    }

    /** 取消当前生效限供（幂等），恢复计划水量。 */
    public RestrictionView cancelRestriction(long windowId, KeyedCommand command) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("windowId", windowId);
        return runCommand(command == null ? null : command.commandKey(), "CANCEL_RESTRICTION",
                params, RestrictionView.class, () -> {
                    lockWindow(windowId);
                    WaterRestriction active = repository.findActiveRestriction(windowId);
                    if (active == null) {
                        throw ApiException.conflict("STATE_CONFLICT", "窗口当前没有生效中的限供");
                    }
                    long now = nowMs();
                    repository.cancelRestriction(active.id(), now);
                    return toView(new WaterRestriction(active.id(), active.windowId(), active.limitVolume(),
                            WaterRestriction.STATUS_CANCELLED, active.createdAt(), Instant.ofEpochMilli(now)));
                });
    }

    /** 查询窗口当前可用容量。 */
    public CapacityView getCapacity(long windowId) {
        WaterWindow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("窗口不存在: " + windowId);
        }
        WaterRestriction active = repository.findActiveRestriction(windowId);
        BigDecimal effective = active != null ? active.limitVolume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedVolume(windowId);
        return new CapacityView(windowId, fmt(window.plannedVolume()),
                active != null ? fmt(active.limitVolume()) : null,
                fmt(effective), fmt(approved), fmt(effective.subtract(approved)));
    }

    /** 查询窗口历史明细（窗口、限供记录、申请列表）。 */
    public WindowHistoryView getHistory(long windowId) {
        WaterWindow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("窗口不存在: " + windowId);
        }
        List<RestrictionView> restrictions = repository.listRestrictionsByWindow(windowId)
                .stream().map(this::toView).toList();
        List<AllocationView> allocations = repository.listAllocationsByWindow(windowId)
                .stream().map(this::toView).toList();
        return new WindowHistoryView(toView(window), restrictions, allocations);
    }

    // ---------- 内部辅助 ----------

    /** 查询申请并锁定其所属窗口行，串行化该窗口上的批准/取消/限供操作。 */
    private WaterAllocation lockWindowOfAllocation(String allocationKey) {
        WaterAllocation allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("申请不存在: " + allocationKey);
        }
        repository.lockWindowById(allocation.windowId());
        // 加锁后重读，确保拿到已提交的最新状态。
        return repository.findAllocationByKey(allocationKey);
    }

    /** 锁定窗口行；窗口不存在时抛 404。 */
    private WaterWindow lockWindow(long windowId) {
        WaterWindow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("窗口不存在: " + windowId);
        }
        return window;
    }

    /** 当前可用总量：无生效限供为计划水量，否则为限供水量。 */
    private BigDecimal effectiveVolume(WaterWindow window) {
        WaterRestriction active = repository.findActiveRestriction(window.id());
        return active != null ? active.limitVolume() : window.plannedVolume();
    }

    /**
     * 幂等执行写命令：事务内先插入命令占位行，数据库唯一键使同键并发请求相互等待；
     * 首事务提交后其余事务插入失败并回读首次结果重放，同参返回首次结果、改参抛 409；
     * 业务失败时占位行随事务回滚，命令键不被占用，可安全重试。
     */
    private <T> T runCommand(String commandKey, String operation, Map<String, Object> params,
            Class<T> type, Supplier<T> action) {
        String key = requireNonBlank(commandKey, "commandKey");
        String paramsHash = hash(operation, params);
        CommandRecord existing = repository.findCommand(key);
        if (existing != null) {
            return replay(existing, operation, paramsHash, type);
        }
        try {
            return transactions.execute(status -> {
                repository.insertCommand(key, operation, paramsHash, 200, null, nowMs());
                T result = action.get();
                repository.updateCommandResponse(key, 200, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException ex) {
            CommandRecord concurrent = repository.findCommand(key);
            if (concurrent != null) {
                return replay(concurrent, operation, paramsHash, type);
            }
            throw ApiException.conflict("DUPLICATE_KEY", "业务键与已有记录冲突");
        }
    }

    /** 重放已存命令：操作与参数指纹一致时返回首次结果，否则抛 409。 */
    private <T> T replay(CommandRecord record, String operation, String paramsHash, Class<T> type) {
        if (!record.operation().equals(operation) || !record.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("COMMAND_CONFLICT",
                    "commandKey 已被不同参数的操作占用: " + record.commandKey());
        }
        try {
            return MAPPER.readValue(record.responseBody(), type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等记录响应体损坏: " + record.commandKey(), ex);
        }
    }

    /** 计算操作与参数的 SHA-256 指纹（十六进制）。 */
    private static String hash(String operation, Map<String, Object> params) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((operation + "\n" + MAPPER.writeValueAsString(params))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new IllegalStateException("无法计算命令参数指纹", ex);
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    /** 校验并解析水量：十进制字符串，最多 3 位小数，大于 0，整数位不超过 16 位；统一归一到 3 位小数。 */
    private static BigDecimal parseVolume(String raw, String field) {
        String text = requireNonBlank(raw, field);
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest(field + " 必须为十进制数字: " + text);
        }
        if (value.scale() > 3) {
            throw ApiException.badRequest(field + " 最多 3 位小数: " + text);
        }
        if (value.signum() <= 0) {
            throw ApiException.badRequest(field + " 必须大于 0: " + text);
        }
        if (value.precision() - value.scale() > 16) {
            throw ApiException.badRequest(field + " 超出可存储范围: " + text);
        }
        return value.setScale(3, RoundingMode.UNNECESSARY);
    }

    /** 解析 ISO-8601 UTC 时刻字符串。 */
    private static Instant parseInstant(String raw, String field) {
        String text = requireNonBlank(raw, field);
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(field + " 必须为 ISO-8601 UTC 时刻: " + text);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    private static long nowMs() {
        return Instant.now().toEpochMilli();
    }

    /** 水量输出格式：固定 3 位小数十进制字符串。 */
    private static String fmt(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private WaterAllocation withStatus(WaterAllocation allocation, String status, long updatedMs) {
        return new WaterAllocation(allocation.id(), allocation.allocationKey(), allocation.windowId(),
                allocation.userId(), allocation.volume(), allocation.applicant(), status,
                allocation.createdAt(), Instant.ofEpochMilli(updatedMs));
    }

    private WindowView toView(WaterWindow window) {
        return new WindowView(window.id(), window.windowKey(), window.channelId(),
                window.startUtc().toString(), window.endUtc().toString(),
                fmt(window.plannedVolume()), window.createdAt().toString());
    }

    private AllocationView toView(WaterAllocation allocation) {
        return new AllocationView(allocation.id(), allocation.allocationKey(), allocation.windowId(),
                allocation.userId(), fmt(allocation.volume()), allocation.applicant(), allocation.status(),
                allocation.createdAt().toString(), allocation.updatedAt().toString());
    }

    private RestrictionView toView(WaterRestriction restriction) {
        return new RestrictionView(restriction.id(), restriction.windowId(), fmt(restriction.limitVolume()),
                restriction.status(), restriction.createdAt().toString(),
                restriction.cancelledAt() != null ? restriction.cancelledAt().toString() : null);
    }
}
