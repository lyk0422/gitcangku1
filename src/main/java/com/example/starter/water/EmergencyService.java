package com.example.starter.water;

import com.example.starter.water.EmergencyRepository.ActivationRow;
import com.example.starter.water.EmergencyRepository.EventRow;
import com.example.starter.water.EmergencyRepository.FlowRow;
import com.example.starter.water.EmergencyRepository.ReserveRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.EmergencyDtos.ActivationBalanceResponse;
import com.example.starter.water.dto.EmergencyDtos.ActivationResponse;
import com.example.starter.water.dto.EmergencyDtos.EmergencyEventResponse;
import com.example.starter.water.dto.EmergencyDtos.EmergencyFlowResponse;
import com.example.starter.water.dto.EmergencyDtos.EventHistoryResponse;
import com.example.starter.water.dto.EmergencyDtos.ReserveResponse;
import com.example.starter.water.dto.EmergencyDtos.WindowReserveResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * 紧急储备水业务服务：管理与普通配水相互独立的紧急储备账本。
 *
 * <p>额度守恒：储备总量播种后恒定，启用只是把水从储备总账预占并转入目标窗口；
 * 核销表示应急耗用（离开两本账），归还把窗口中未核销的水退回储备总账。
 * 任何审批都不会增加总水量。</p>
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位命令行再执行业务并写回响应，
 * 同键同参重放首次结果，同键改参返回 409；失败回滚不占用命令键。
 * 此外启用请求自带 requestId：同键（requestId）同事件同窗口的重放返回首次转入数量，
 * 换事件或窗口返回 409。</p>
 *
 * <p>并发：启用先锁目标窗口行（与普通批准/转让共用窗口锁），再锁事件行与储备总账单行，
 * 按事务提交顺序裁决，保证储备不超发、普通窗口不超额、两本账共同守恒。</p>
 */
@Service
public class EmergencyService {

    static final String EVENT_ACTIVE = "ACTIVE";
    static final String EVENT_CANCELLED = "CANCELLED";
    static final String FLOW_WRITE_OFF = "WRITE_OFF";
    static final String FLOW_RETURN = "RETURN";

    private final WaterRepository waterRepository;
    private final EmergencyRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public EmergencyService(WaterRepository waterRepository, EmergencyRepository repository,
                            PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.waterRepository = waterRepository;
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 紧急事件
    // ------------------------------------------------------------------

    /** 登记有效（ACTIVE）紧急事件；重复 eventKey 返回 409。 */
    public EmergencyEventResponse registerEvent(String commandKey, String eventKey, String title,
                                                String description) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("eventKey", eventKey);
        String safeTitle = requireText("title", title, 256);
        String safeDescription = description == null ? "" : description.trim();
        if (safeDescription.length() > 1024) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "description 最长 1024 字符");
        }
        String params = "EVENT_REGISTER|" + eventKey + "|" + safeTitle + "|" + safeDescription;
        return runCommand("EVENT_REGISTER", commandKey, params, EmergencyEventResponse.class, () -> {
            try {
                repository.insertEvent(eventKey, safeTitle, safeDescription, WaterService.nowNanos());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("EVENT_KEY_REUSED", "eventKey 已被使用: " + eventKey);
            }
            return toEventResponse(repository.findEvent(eventKey));
        });
    }

    /** 将事件置为失效；重复取消返回 409，失效事件不能再启用储备。 */
    public EmergencyEventResponse cancelEvent(String commandKey, String eventKey) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("eventKey", eventKey);
        String params = "EVENT_CANCEL|" + eventKey;
        return runCommand("EVENT_CANCEL", commandKey, params, EmergencyEventResponse.class, () -> {
            EventRow event = repository.lockEvent(eventKey);
            if (event == null) {
                throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
            }
            if (EVENT_CANCELLED.equals(event.status())) {
                throw ApiException.conflict("EVENT_ALREADY_CANCELLED", "事件已失效，不能重复取消");
            }
            repository.cancelEvent(event.id(), WaterService.nowNanos());
            return toEventResponse(repository.findEvent(eventKey));
        });
    }

    // ------------------------------------------------------------------
    // 启用 / 核销 / 归还
    // ------------------------------------------------------------------

    /**
     * 紧急启用：两名不同水务角色确认同一 ACTIVE 事件与同一目标窗口后，
     * 从储备账本预占额度并转入该窗口。储备不足、事件失效、确认人重复均返回 422 且不动任何账本。
     */
    public ActivationResponse activate(String commandKey, String requestId, String eventKey, Long windowId,
                                       String volume, String confirmerA, String confirmerB) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("eventKey", eventKey);
        WaterService.requireKey("confirmerA", confirmerA);
        WaterService.requireKey("confirmerB", confirmerB);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = WaterService.parseAmount("volume", volume);
        if (confirmerA.equals(confirmerB)) {
            throw ApiException.unprocessable("DUPLICATE_CONFIRMER",
                    "两位确认人必须是不同水务角色: " + confirmerA);
        }
        String params = "RESERVE_ACTIVATE|" + requestId + "|" + eventKey + "|" + windowId + "|"
                + qty.toPlainString() + "|" + confirmerA + "|" + confirmerB;
        return runCommand("RESERVE_ACTIVATE", commandKey, params, ActivationResponse.class, () -> {
            // 固定加锁顺序：目标窗口 -> 事件 -> 储备总账，与普通分配按窗口锁串行裁决
            WindowRow window = waterRepository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            EventRow event = repository.lockEvent(eventKey);
            if (event == null) {
                throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
            }
            if (!EVENT_ACTIVE.equals(event.status())) {
                throw ApiException.unprocessable("NON_EMERGENCY_EVENT",
                        "仅有效紧急事件可申请储备水，事件状态: " + event.status());
            }
            ReserveRow reserve = repository.lockReserve();

            // requestId 级幂等（独立于 commandKey）：同事件同窗口重放首次转入数量，换事件/窗口 409
            ActivationRow existing = repository.findActivation(requestId);
            if (existing != null) {
                return replayActivation(existing, eventKey, windowId);
            }
            BigDecimal available = reserve.totalVolume().subtract(reserve.reservedVolume());
            if (qty.compareTo(available) > 0) {
                throw ApiException.unprocessable("RESERVE_INSUFFICIENT",
                        "储备可用 " + WaterService.fmt(available) + " 不足，无法预占 " + WaterService.fmt(qty));
            }
            long now = WaterService.nowNanos();
            try {
                repository.insertActivation(requestId, eventKey, windowId, qty, confirmerA, confirmerB, now);
            } catch (DuplicateKeyException e) {
                // 并发以另一 commandKey 复用同一 requestId：按首次记录裁决
                ActivationRow raced = repository.findActivation(requestId);
                return replayActivation(raced, eventKey, windowId);
            }
            repository.increaseReserved(qty, now);
            return toActivationResponse(repository.findActivation(requestId));
        });
    }

    /** 核销：窗口中尚未核销且未归还的数量才能核销，超量返回 422 且不动任何账本。 */
    public ActivationBalanceResponse writeOff(String commandKey, String requestId, String volume, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal qty = WaterService.parseAmount("volume", volume);
        String params = "RESERVE_WRITE_OFF|" + requestId + "|" + qty.toPlainString() + "|" + actor;
        return runCommand("RESERVE_WRITE_OFF", commandKey, params, ActivationBalanceResponse.class, () -> {
            ActivationRow activation = repository.lockActivation(requestId);
            if (activation == null) {
                throw ApiException.notFound("ACTIVATION_NOT_FOUND", "紧急启用不存在: " + requestId);
            }
            if (qty.compareTo(activation.outstanding()) > 0) {
                throw ApiException.unprocessable("WRITE_OFF_EXCEEDS_OUTSTANDING",
                        "核销 " + WaterService.fmt(qty) + " 超过窗口尚未核销数量 "
                                + WaterService.fmt(activation.outstanding()));
            }
            long now = WaterService.nowNanos();
            repository.addWrittenOff(activation.id(), qty);
            repository.insertFlow(requestId, activation.eventKey(), activation.windowId(),
                    FLOW_WRITE_OFF, qty, actor, now);
            // 核销是应急耗用：水离开窗口但不回储备，储备总账已预占数量保持不变
            return toBalanceResponse(repository.findActivation(requestId));
        });
    }

    /** 归还：不得超过该启用已转入窗口且尚未核销（含未重复归还）的数量，超量返回 422 且不动任何账本。 */
    public ActivationBalanceResponse reserveReturn(String commandKey, String requestId, String volume,
                                                   String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal qty = WaterService.parseAmount("volume", volume);
        String params = "RESERVE_RETURN|" + requestId + "|" + qty.toPlainString() + "|" + actor;
        return runCommand("RESERVE_RETURN", commandKey, params, ActivationBalanceResponse.class, () -> {
            // 固定加锁顺序：启用审批 -> 储备总账
            ActivationRow activation = repository.lockActivation(requestId);
            if (activation == null) {
                throw ApiException.notFound("ACTIVATION_NOT_FOUND", "紧急启用不存在: " + requestId);
            }
            ReserveRow reserve = repository.lockReserve();
            if (qty.compareTo(activation.outstanding()) > 0) {
                throw ApiException.unprocessable("RETURN_EXCEEDS_OUTSTANDING",
                        "归还 " + WaterService.fmt(qty) + " 超过该启用已启用且尚未核销的数量 "
                                + WaterService.fmt(activation.outstanding()));
            }
            long now = WaterService.nowNanos();
            repository.addReturned(activation.id(), qty);
            repository.decreaseReserved(qty, now);
            repository.insertFlow(requestId, activation.eventKey(), activation.windowId(),
                    FLOW_RETURN, qty, actor, now);
            return toBalanceResponse(repository.findActivation(requestId));
        });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 查询储备总账：总量恒定，可用 = 总量 - 已预占。 */
    public ReserveResponse getReserve() {
        ReserveRow row = repository.findReserve();
        BigDecimal available = row.totalVolume().subtract(row.reservedVolume());
        return new ReserveResponse(WaterService.fmt(row.totalVolume()), WaterService.fmt(row.reservedVolume()),
                WaterService.fmt(available));
    }

    /** 查询目标窗口的储备预占余额、核销与归还流水；窗口不存在返回 404。 */
    public WindowReserveResponse getWindowReserve(long windowId) {
        WindowRow window = waterRepository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<ActivationRow> activations = repository.listActivationsByWindow(windowId);
        List<FlowRow> flows = repository.listFlowsByWindow(windowId);
        BigDecimal occupied = BigDecimal.ZERO;
        BigDecimal writtenOff = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        for (ActivationRow activation : activations) {
            occupied = occupied.add(activation.outstanding());
            writtenOff = writtenOff.add(activation.writtenOffVolume());
            returned = returned.add(activation.returnedVolume());
        }
        List<ActivationBalanceResponse> activationViews = activations.stream()
                .map(this::toBalanceResponse).toList();
        List<EmergencyFlowResponse> flowViews = flows.stream().map(this::toFlowResponse).toList();
        return new WindowReserveResponse(windowId, WaterService.fmt(occupied), WaterService.fmt(writtenOff),
                WaterService.fmt(returned), activationViews, flowViews);
    }

    /** 查询事件历史：事件本身 + 全部启用审批与确认人 + 全部核销/归还流水；事件不存在返回 404。 */
    public EventHistoryResponse getEventHistory(String eventKey) {
        EventRow event = repository.findEvent(eventKey);
        if (event == null) {
            throw ApiException.notFound("EVENT_NOT_FOUND", "紧急事件不存在: " + eventKey);
        }
        List<ActivationRow> activations = repository.listActivationsByEvent(eventKey);
        List<FlowRow> flows = repository.listFlowsByEvent(eventKey);
        return new EventHistoryResponse(toEventResponse(event),
                activations.stream().map(this::toActivationResponse).toList(),
                flows.stream().map(this::toFlowResponse).toList());
    }

    // ------------------------------------------------------------------
    // 幂等命令框架（与 WaterService 同一 command_log 与重放语义）
    // ------------------------------------------------------------------

    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = waterRepository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                waterRepository.insertCommand(commandKey, operation, params, WaterService.nowNanos());
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

    /** requestId 级裁决：同事件同窗口重放首次结果，换事件或窗口返回 409。 */
    private ActivationResponse replayActivation(ActivationRow existing, String eventKey, long windowId) {
        if (existing == null) {
            throw ApiException.conflict("COMMAND_CONFLICT", "相同 requestId 的启用正在处理，请重试");
        }
        if (!existing.eventKey().equals(eventKey) || existing.windowId() != windowId) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已用于其他事件或窗口: " + existing.requestId());
        }
        return toActivationResponse(existing);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 最长 " + maxLength + " 字符");
        }
        return trimmed;
    }

    private EmergencyEventResponse toEventResponse(EventRow row) {
        return new EmergencyEventResponse(row.eventKey(), row.title(), row.description(), row.status(),
                WaterService.toIso(row.createdNanos()),
                row.cancelledNanos() == null ? null : WaterService.toIso(row.cancelledNanos()));
    }

    private ActivationResponse toActivationResponse(ActivationRow row) {
        return new ActivationResponse(row.id(), row.requestId(), row.eventKey(), row.windowId(),
                WaterService.fmt(row.volume()), row.confirmerA(), row.confirmerB(), row.status(),
                WaterService.toIso(row.createdNanos()));
    }

    private ActivationBalanceResponse toBalanceResponse(ActivationRow row) {
        return new ActivationBalanceResponse(row.requestId(), row.eventKey(), row.windowId(),
                WaterService.fmt(row.volume()), WaterService.fmt(row.writtenOffVolume()),
                WaterService.fmt(row.returnedVolume()), WaterService.fmt(row.outstanding()), row.status());
    }

    private EmergencyFlowResponse toFlowResponse(FlowRow row) {
        return new EmergencyFlowResponse(row.id(), row.requestId(), row.eventKey(), row.windowId(),
                row.flowType(), WaterService.fmt(row.volume()), row.actor(),
                WaterService.toIso(row.createdNanos()));
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
