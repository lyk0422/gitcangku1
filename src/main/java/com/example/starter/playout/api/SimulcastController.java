package com.example.starter.playout.api;

import com.example.starter.playout.SimulcastService;
import com.example.starter.playout.api.Dtos.ChannelSimulcastPlaceholdersResponse;
import com.example.starter.playout.api.Dtos.CreateSimulcastLockRequest;
import com.example.starter.playout.api.Dtos.RevokeSimulcastLockRequest;
import com.example.starter.playout.api.Dtos.SimulcastLockResponse;
import com.example.starter.playout.api.Dtos.SimulcastRevocationHistoryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 多频道联播 REST API。创建/撤销携带 requestId 幂等；创建校验失败返回 422 逐频道原因；
 * 撤销在计划播出时刻之后或重复撤销返回 409。
 */
@Validated
@RestController
@RequestMapping("/api")
public class SimulcastController {

    private final SimulcastService service;

    public SimulcastController(SimulcastService service) {
        this.service = service;
    }

    /** 创建多频道联播锁定（2～8 个频道、同一素材与计划时刻，整组校验，幂等）。 */
    @PostMapping("/simulcast-locks")
    public SimulcastLockResponse createSimulcastLock(
            @Valid @RequestBody CreateSimulcastLockRequest request) {
        return service.createSimulcastLock(request);
    }

    /** 撤销整个联播组（须在计划播出时刻之前，同时释放全部频道占位，幂等）。 */
    @PostMapping("/simulcast-locks/{simulcastKey}/revoke")
    public SimulcastLockResponse revokeSimulcastLock(
            @PathVariable @NotBlank String simulcastKey,
            @Valid @RequestBody RevokeSimulcastLockRequest request) {
        return service.revokeSimulcastLock(simulcastKey, request.requestId());
    }

    /** 查询联播组明细，含逐频道占位与固化授权。 */
    @GetMapping("/simulcast-locks/{simulcastKey}")
    public SimulcastLockResponse simulcastLock(@PathVariable @NotBlank String simulcastKey) {
        return service.getSimulcastLock(simulcastKey);
    }

    /** 查询频道+业务日下仍生效的联播占位。 */
    @GetMapping("/channels/{channelId}/simulcast-placeholders")
    public ChannelSimulcastPlaceholdersResponse channelPlaceholders(
            @PathVariable @NotBlank String channelId,
            @RequestParam String businessDay) {
        return service.getChannelPlaceholders(channelId, parseBusinessDay(businessDay));
    }

    /** 查询联播撤销历史（不可变记录，按撤销时间倒序）。 */
    @GetMapping("/simulcast-revocations")
    public SimulcastRevocationHistoryResponse simulcastRevocations() {
        return service.listRevocations();
    }

    private static LocalDate parseBusinessDay(String businessDay) {
        try {
            return LocalDate.parse(businessDay);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("业务日格式应为 yyyy-MM-dd: " + businessDay);
        }
    }
}
