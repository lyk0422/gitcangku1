package com.example.starter.water;

import com.example.starter.water.dto.EmergencyDtos.ActivateRequest;
import com.example.starter.water.dto.EmergencyDtos.ActivationBalanceResponse;
import com.example.starter.water.dto.EmergencyDtos.ActivationResponse;
import com.example.starter.water.dto.EmergencyDtos.EmergencyEventResponse;
import com.example.starter.water.dto.EmergencyDtos.EventHistoryResponse;
import com.example.starter.water.dto.EmergencyDtos.RegisterEventRequest;
import com.example.starter.water.dto.EmergencyDtos.ReserveResponse;
import com.example.starter.water.dto.EmergencyDtos.ReturnRequest;
import com.example.starter.water.dto.EmergencyDtos.WindowReserveResponse;
import com.example.starter.water.dto.EmergencyDtos.WriteOffRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 紧急储备水 REST 接口。与普通配水接口（/api/windows、/api/allocations 等）相互独立：
 * 普通排班接口不读取储备账本，储备操作也不改变普通配水的计划/已批准数量。
 *
 * <p>所有写操作携带 commandKey 保证幂等；核销/归还通过 X-Actor-Id 识别水务角色。</p>
 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    /** 登记有效紧急事件。 */
    @PostMapping("/events")
    public EmergencyEventResponse registerEvent(@RequestBody RegisterEventRequest request) {
        return service.registerEvent(request.commandKey(), request.eventKey(), request.title(),
                request.description());
    }

    /** 将紧急事件置为失效（非紧急用途，此后不可再启用储备）。 */
    @PostMapping("/events/{eventKey}/cancel")
    public EmergencyEventResponse cancelEvent(@PathVariable String eventKey,
                                              @RequestBody com.example.starter.water.dto.Dtos.CommandRequest request) {
        return service.cancelEvent(request.commandKey(), eventKey);
    }

    /** 两名不同水务角色确认同一事件与目标窗口后，从储备账本预占额度并转入该窗口。 */
    @PostMapping("/activations")
    public ActivationResponse activate(@RequestBody ActivateRequest request) {
        return service.activate(request.commandKey(), request.requestId(), request.eventKey(),
                request.windowId(), request.volume(), request.confirmerA(), request.confirmerB());
    }

    /** 核销启用水量（应急耗用，离开窗口账本）。 */
    @PostMapping("/activations/{requestId}/write-off")
    public ActivationBalanceResponse writeOff(@PathVariable String requestId,
                                              @RequestBody WriteOffRequest request,
                                              @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOff(request.commandKey(), requestId, request.volume(), actor);
    }

    /** 归还未核销启用水量（退回储备总账）。 */
    @PostMapping("/activations/{requestId}/return")
    public ActivationBalanceResponse reserveReturn(@PathVariable String requestId,
                                                   @RequestBody ReturnRequest request,
                                                   @RequestHeader("X-Actor-Id") String actor) {
        return service.reserveReturn(request.commandKey(), requestId, request.volume(), actor);
    }

    /** 查询储备总账（总量、已预占、可用）。 */
    @GetMapping("/reserve")
    public ReserveResponse getReserve() {
        return service.getReserve();
    }

    /** 查询目标窗口的储备预占、核销与归还流水。 */
    @GetMapping("/windows/{windowId}")
    public WindowReserveResponse getWindowReserve(@PathVariable long windowId) {
        return service.getWindowReserve(windowId);
    }

    /** 查询事件历史（事件、全部启用审批与确认人、核销/归还流水）。 */
    @GetMapping("/events/{eventKey}/history")
    public EventHistoryResponse getEventHistory(@PathVariable String eventKey) {
        return service.getEventHistory(eventKey);
    }
}
