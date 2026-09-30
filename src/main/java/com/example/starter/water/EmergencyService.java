package com.example.starter.water;

import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.EmergencyActivationResponse;
import com.example.starter.water.dto.Dtos.EmergencyConfirmationView;
import com.example.starter.water.dto.Dtos.EmergencyEventDetailResponse;
import com.example.starter.water.dto.Dtos.EmergencyEventResponse;
import com.example.starter.water.dto.Dtos.EmergencyFlowListResponse;
import com.example.starter.water.dto.Dtos.EmergencyFlowResponse;
import com.example.starter.water.dto.Dtos.ReserveResponse;
import com.example.starter.water.dto.Dtos.WindowEmergencyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * 紧急储备业务服务：独立储备账本，仅 VALID 紧急事件经两位不同水务角色确认后可启用。
 *
 * <p>启用语义：储备扣减与窗口预占同事务完成，紧急水进入目标窗口后占用窗口容量
 * （普通已批准持有额度 + 紧急未结清量不得超过窗口当前可用总量），审批本身不创造水量；
 * 核销表示已耗用（不回补储备、释放窗口预占），归还表示未耗用退回（回补储备、释放窗口预占）。</p>
 *
 * <p>幂等：写命令携带 commandKey，复用 command_log：同键同参重放首次结果，同键改参返回 409；
 * 业务失败随事务回滚，不占用 commandKey。同一 requestId 在启用后重放返回首次转入数量，
 * 换事件或窗口返回 409。</p>
 *
 * <p>并发：统一加锁顺序 supply_window → emergency_event → reserve_account → emergency_request，
 * 紧急启用与普通批准/限供在窗口行锁上串行裁决，两本账与窗口占用共同守恒。</p>
 */
@Service
public class EmergencyService {

    static final String EVENT_VALID = "VALID";
    static final String EVENT_INVALID = "INVALID";
    static final String REQUEST_PENDING = "PENDING";
    static final String REQUEST_ACTIVE = "ACTIVE";
    static final String REQUEST_SETTLED = "SETTLED";
    static final String FLOW_ACTIVATION = "ACTIVATION";
    static final String FLOW_WRITEOFF = "WRITEOFF";
    static final String FLOW_RETURN = "RETURN";

    private final EmergencyRepository repository;
    private final WaterRepository waterRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EmergencyService(EmergencyRepository repository, WaterRepository waterRepository,
                            PlatformTransactionManager transactionManager, ObjectMapper objectMapper,
                            Clock clock) {
        this.repository = repository;
        this.waterRepository = waterRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 登记有效紧急事件。 */
    public EmergencyEventResponse createEvent(String commandKey, String eventKey, String title) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("eventKey", eventKey);
        requireTitle(title);
        String trimmedTitle = title.trim();
        String params = "EMERGENCY_EVENT_CREATE|" + eventKey + "|" + trimmedTitle;
        return runCommand("EMERGENCY_EVENT_CREATE", commandKey, params, EmergencyEventResponse.class, () -> {
            try {
                repository.insertEvent(eventKey, trimmedTitle, WaterService.nowNanos(clock));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("EVENT_KEY_REUSED", "紧急事件键已被使用: " + eventKey);
            }
            return toEventResponse(repository.findEventByKey(eventKey));
        });
    }

    /** 作废紧急事件（非紧急用途）；作废后不可再发起启用，历史启用与确认记录保留。 */
    public EmergencyEventResponse invalidateEvent(String commandKey, String eventKey) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("eventKey", eventKey);
        String params = "EMERGENCY_EVENT_INVALIDATE|" + eventKey;
        return runCommand("EMERGENCY_EVENT_INVALIDATE", commandKey, params, EmergencyEventResponse.class, () -> {
            EmergencyRepository.EventRow event = repository.lockEventByKey(eventKey);
            if (event == null) {
                throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
            }
            if (EVENT_INVALID.equals(event.status())) {
                throw ApiException.conflict("EVENT_ALREADY_INVALID", "事件已作废，不能重复作废");
            }
            repository.invalidateEvent(eventKey, WaterService.nowNanos(clock));
            return toEventResponse(repository.findEventByKey(eventKey));
        });
    }

    /** 查询事件明细及其全部启用请求（含当时两位确认人）。 */
    public EmergencyEventDetailResponse getEvent(String eventKey) {
        EmergencyRepository.EventRow event = repository.findEventByKey(eventKey);
        if (event == null) {
            throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
        }
        List<EmergencyActivationResponse> requests = repository.listRequestsByEvent(eventKey).stream()
                .map(this::toActivationResponse).toList();
        return new EmergencyEventDetailResponse(toEventResponse(event), requests);
    }

    // ------------------------------------------------------------------
    // 双角色确认启用
    // ------------------------------------------------------------------

    /**
     * 双角色确认启用：首位确认建 PENDING（不扣储备），第二位不同角色、不同确认人确认后
     * 扣减储备并在目标窗口预占。储备不足、事件非紧急、重复角色/确认人返回 422 且不动任何账本；
     * 已启用 requestId 带原事件/窗口/数量重放返回首次转入结果，换事件或窗口返回 409。
     */
    public EmergencyActivationResponse confirmActivation(String commandKey, String requestId, String eventKey,
                                                         Long windowId, String amount, String role,
                                                         String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("eventKey", eventKey);
        WaterService.requireKey("X-Role-Id", role);
        WaterService.requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = WaterService.parseAmount("amount", amount);
        String params = "EMERGENCY_CONFIRM|" + requestId + "|" + eventKey + "|" + windowId + "|"
                + qty.toPlainString() + "|" + role + "|" + actor;
        return runCommand("EMERGENCY_CONFIRM", commandKey, params, EmergencyActivationResponse.class,
                () -> doConfirm(requestId, eventKey, windowId, qty, role, actor));
    }

    private EmergencyActivationResponse doConfirm(String requestId, String eventKey, long windowId,
                                                  BigDecimal qty, String role, String actor) {
        // 固定加锁顺序：窗口 → 事件 → 储备 → 请求；窗口锁已将同一目标窗口的确认/普通分配串行化
        WindowRow window = waterRepository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        EmergencyRepository.EventRow event = repository.lockEventByKey(eventKey);
        if (event == null) {
            throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
        }
        repository.lockReserve();
        EmergencyRepository.RequestRow existing = repository.lockRequestByRequestId(requestId);
        if (existing != null) {
            return confirmExisting(existing, window, requestId, eventKey, windowId, qty, role, actor, event);
        }
        if (!EVENT_VALID.equals(event.status())) {
            throw ApiException.unprocessable("EVENT_NOT_EMERGENCY",
                    "仅有效紧急事件可申请储备水，事件当前状态: " + event.status());
        }
        long now = WaterService.nowNanos(clock);
        try {
            repository.insertRequest(requestId, eventKey, windowId, qty, now);
        } catch (DuplicateKeyException e) {
            // 并发以不同事件/窗口参数复用 requestId：拒绝且不动账本
            throw ApiException.conflict("REQUEST_ID_REUSED", "启用 requestId 已被使用: " + requestId);
        }
        repository.insertConfirmation(requestId, 1, role, actor, now);
        return toActivationResponse(repository.findRequestByRequestId(requestId));
    }

    private EmergencyActivationResponse confirmExisting(EmergencyRepository.RequestRow existing,
                                                        WindowRow window, String requestId, String eventKey,
                                                        long windowId, BigDecimal qty, String role,
                                                        String actor, EmergencyRepository.EventRow event) {
        // 同一 requestId 换事件、窗口或数量 -> 409
        if (!existing.eventKey().equals(eventKey) || existing.windowId() != windowId
                || existing.amount().compareTo(qty) != 0) {
            throw ApiException.conflict("REQUEST_TARGET_CHANGED",
                    "启用 requestId 已绑定其他事件/窗口/数量，拒绝重放: " + requestId);
        }
        // 调用方已持有窗口/事件/储备/请求行锁，锁内读取确认记录
        List<EmergencyRepository.ConfirmationRow> confirmations = repository.listConfirmations(requestId);
        if (!REQUEST_PENDING.equals(existing.status())) {
            // ACTIVE/SETTLED：仅两位确认人本人重放首次转入数量；未参与的第三人复用 requestId 返回 409
            boolean participant = confirmations.stream()
                    .anyMatch(c -> c.roleCode().equals(role) && c.confirmer().equals(actor));
            if (!participant) {
                throw ApiException.conflict("REQUEST_ALREADY_ACTIVATED",
                        "启用 requestId 已完成确认并转入储备水，未参与确认的角色不能重复启用: " + requestId);
            }
            return toActivationResponse(repository.findRequestByRequestId(requestId));
        }
        for (EmergencyRepository.ConfirmationRow confirmation : confirmations) {
            if (confirmation.roleCode().equals(role)) {
                throw ApiException.unprocessable("DUPLICATE_ROLE_CONFIRMATION",
                        "角色 " + role + " 已确认该启用请求，两位确认人角色必须不同");
            }
            if (confirmation.confirmer().equals(actor)) {
                throw ApiException.unprocessable("DUPLICATE_CONFIRMER",
                        "确认人 " + actor + " 已确认该启用请求，两位确认人必须不同");
            }
        }
        if (!EVENT_VALID.equals(event.status())) {
            throw ApiException.unprocessable("EVENT_NOT_EMERGENCY",
                    "仅有效紧急事件可申请储备水，事件当前状态: " + event.status());
        }
        EmergencyRepository.ReserveRow reserve = repository.lockReserve();
        if (reserve.remainingVolume().compareTo(qty) < 0) {
            throw ApiException.unprocessable("RESERVE_INSUFFICIENT",
                    "紧急储备剩余 " + WaterService.fmt(reserve.remainingVolume())
                            + " 不足，无法启用 " + WaterService.fmt(qty));
        }
        BigDecimal normalApproved = waterRepository.sumApprovedAmount(windowId);
        BigDecimal emergencyOutstanding = repository.sumOutstandingByWindow(windowId);
        BigDecimal available = availableTotal(window);
        if (normalApproved.add(emergencyOutstanding).add(qty).compareTo(available) > 0) {
            throw ApiException.unprocessable("WINDOW_CAPACITY_EXCEEDED",
                    "启用后窗口占用将超过当前可用总量 " + WaterService.fmt(available));
        }
        long now = WaterService.nowNanos(clock);
        repository.decrementReserve(qty, now);
        repository.activateRequest(requestId, now);
        repository.insertConfirmation(requestId, 2, role, actor, now);
        repository.insertFlow(requestId, eventKey, windowId, FLOW_ACTIVATION, qty, actor, now);
        return toActivationResponse(repository.findRequestByRequestId(requestId));
    }

    // ------------------------------------------------------------------
    // 核销与归还
    // ------------------------------------------------------------------

    /** 核销：标记已耗用数量，不回补储备，不得超过当前未结清量。 */
    public EmergencyActivationResponse writeOff(String commandKey, String requestId, String amount,
                                                String actor) {
        return settle(commandKey, requestId, amount, actor, "EMERGENCY_WRITEOFF", FLOW_WRITEOFF, false);
    }

    /** 归还：未耗用数量回补储备并释放窗口预占，不得超过当前未结清量。 */
    public EmergencyActivationResponse returnWater(String commandKey, String requestId, String amount,
                                                   String actor) {
        return settle(commandKey, requestId, amount, actor, "EMERGENCY_RETURN", FLOW_RETURN, true);
    }

    private EmergencyActivationResponse settle(String commandKey, String requestId, String amount,
                                               String actor, String operation, String flowType,
                                               boolean refundReserve) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal qty = WaterService.parseAmount("amount", amount);
        String params = operation + "|" + requestId + "|" + qty.toPlainString() + "|" + actor;
        return runCommand(operation, commandKey, params, EmergencyActivationResponse.class, () -> {
            EmergencyRepository.RequestRow found = repository.findRequestByRequestId(requestId);
            if (found == null) {
                throw ApiException.notFound("ACTIVATION_NOT_FOUND", "紧急启用请求不存在: " + requestId);
            }
            // 固定加锁顺序：窗口 → 储备 → 请求
            WindowRow window = waterRepository.lockWindowById(found.windowId());
            repository.lockReserve();
            EmergencyRepository.RequestRow request = repository.lockRequestByRequestId(requestId);
            if (REQUEST_PENDING.equals(request.status())) {
                throw ApiException.conflict("REQUEST_NOT_ACTIVE",
                        "启用请求尚未完成双角色确认，不能" + (refundReserve ? "归还" : "核销"));
            }
            BigDecimal outstanding = request.outstanding();
            if (outstanding.signum() <= 0 || qty.compareTo(outstanding) > 0) {
                throw ApiException.unprocessable("SETTLEMENT_EXCEEDS_OUTSTANDING",
                        (refundReserve ? "归还" : "核销") + "数量 " + WaterService.fmt(qty)
                                + " 超过该事件已启用且尚未核销的数量 " + WaterService.fmt(outstanding));
            }
            long now = WaterService.nowNanos(clock);
            if (refundReserve) {
                repository.incrementReserve(qty, now);
                repository.addReturned(requestId, qty, now);
            } else {
                repository.addWrittenOff(requestId, qty, now);
            }
            repository.insertFlow(requestId, request.eventKey(), request.windowId(), flowType, qty,
                    actor, now);
            EmergencyRepository.RequestRow updated = repository.lockRequestByRequestId(requestId);
            if (updated.outstanding().signum() == 0) {
                repository.settleRequest(requestId, now);
            }
            return toActivationResponse(repository.findRequestByRequestId(requestId));
        });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 查询启用请求当前状态（含两位确认人与储备剩余快照）。 */
    public EmergencyActivationResponse getRequest(String requestId) {
        EmergencyRepository.RequestRow request = repository.findRequestByRequestId(requestId);
        if (request == null) {
            throw ApiException.notFound("ACTIVATION_NOT_FOUND", "紧急启用请求不存在: " + requestId);
        }
        return toActivationResponse(request);
    }

    /** 查询储备账本（独立账本，普通排班接口不读取）。 */
    public ReserveResponse getReserve() {
        EmergencyRepository.ReserveRow reserve = repository.findReserve();
        if (reserve == null) {
            throw new IllegalStateException("储备账户尚未初始化");
        }
        BigDecimal activated = repository.sumFlowByType(FLOW_ACTIVATION);
        BigDecimal writtenOff = repository.sumFlowByType(FLOW_WRITEOFF);
        BigDecimal returned = repository.sumFlowByType(FLOW_RETURN);
        BigDecimal outstanding = activated.subtract(writtenOff).subtract(returned);
        return new ReserveResponse(WaterService.fmt(reserve.totalVolume()),
                WaterService.fmt(reserve.remainingVolume()), WaterService.fmt(activated),
                WaterService.fmt(writtenOff), WaterService.fmt(returned), WaterService.fmt(outstanding),
                WaterService.toIso(reserve.updatedNanos()));
    }

    /** 查询目标窗口的紧急水预占（普通排班容量不直接读取储备账本）。 */
    public WindowEmergencyResponse getWindowEmergency(long windowId) {
        requireWindow(windowId);
        BigDecimal outstanding = repository.sumOutstandingByWindow(windowId);
        List<EmergencyActivationResponse> requests = repository.listActiveRequestsByWindow(windowId).stream()
                .map(this::toActivationResponse).toList();
        return new WindowEmergencyResponse(windowId, WaterService.fmt(outstanding), requests);
    }

    /** 查询某启用单的启用/核销/归还流水。 */
    public EmergencyFlowListResponse listRequestFlows(String requestId) {
        if (repository.findRequestByRequestId(requestId) == null) {
            throw ApiException.notFound("ACTIVATION_NOT_FOUND", "紧急启用请求不存在: " + requestId);
        }
        List<EmergencyFlowResponse> flows = repository.listFlowsByRequest(requestId).stream()
                .map(this::toFlowResponse).toList();
        return new EmergencyFlowListResponse(requestId, flows);
    }

    /** 查询某目标窗口的紧急流水。 */
    public EmergencyFlowListResponse listWindowFlows(long windowId) {
        requireWindow(windowId);
        List<EmergencyFlowResponse> flows = repository.listFlowsByWindow(windowId).stream()
                .map(this::toFlowResponse).toList();
        return new EmergencyFlowListResponse(null, flows);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架（与 WaterService 同一 command_log，互不复用键即可）
    // ------------------------------------------------------------------

    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = waterRepository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                waterRepository.insertCommand(commandKey, operation, params, WaterService.nowNanos(clock));
                T result = business.get();
                waterRepository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = waterRepository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private BigDecimal availableTotal(WindowRow window) {
        var active = waterRepository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    private WindowRow requireWindow(long windowId) {
        WindowRow window = waterRepository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return window;
    }

    private EmergencyEventResponse toEventResponse(EmergencyRepository.EventRow row) {
        return new EmergencyEventResponse(row.eventKey(), row.title(), row.status(),
                WaterService.toIso(row.createdNanos()),
                row.invalidatedNanos() == null ? null : WaterService.toIso(row.invalidatedNanos()));
    }

    private EmergencyActivationResponse toActivationResponse(EmergencyRepository.RequestRow row) {
        List<EmergencyConfirmationView> confirmations = repository.listConfirmations(row.requestId()).stream()
                .map(this::toConfirmationView).toList();
        BigDecimal outstanding = REQUEST_PENDING.equals(row.status()) ? BigDecimal.ZERO : row.outstanding();
        EmergencyRepository.ReserveRow reserve = repository.findReserve();
        String reserveRemaining = reserve == null ? null : WaterService.fmt(reserve.remainingVolume());
        return new EmergencyActivationResponse(row.requestId(), row.eventKey(), row.windowId(), row.status(),
                WaterService.fmt(row.amount()), WaterService.fmt(row.writtenOffAmount()),
                WaterService.fmt(row.returnedAmount()), WaterService.fmt(outstanding), reserveRemaining,
                confirmations, WaterService.toIso(row.createdNanos()),
                row.activatedNanos() == null ? null : WaterService.toIso(row.activatedNanos()));
    }

    private EmergencyConfirmationView toConfirmationView(EmergencyRepository.ConfirmationRow row) {
        return new EmergencyConfirmationView(row.seq(), row.roleCode(), row.confirmer(),
                WaterService.toIso(row.createdNanos()));
    }

    private EmergencyFlowResponse toFlowResponse(EmergencyRepository.FlowRow row) {
        return new EmergencyFlowResponse(row.id(), row.requestId(), row.eventKey(), row.windowId(),
                row.flowType(), WaterService.fmt(row.amount()), row.actor(),
                WaterService.toIso(row.createdNanos()));
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "title 不能为空");
        }
        if (title.trim().length() > 256) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "title 最长 256 字符");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
