package com.example.starter.web;

import com.example.starter.service.TransferService;
import com.example.starter.service.TransferService.TransferView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点间标签交接：发起、目标站点确认、发起站点取消。
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    /**
     * 交接发起请求。
     *
     * @param requestId 幂等请求键
     * @param tagCode 标签编号
     * @param fromStation 发起站点（必须为当前持有站点）
     * @param toStation 接收站点编号
     * @param initiatedAt 发起时刻（UTC）；缺省取服务器当前时刻
     */
    public record InitiateRequest(@NotBlank String requestId, @NotBlank String tagCode,
            @NotBlank String fromStation, @NotBlank String toStation, Instant initiatedAt) {
    }

    /**
     * 交接确认请求。
     *
     * @param stationCode 确认站点（必须为接收站点）
     * @param confirmedAt 确认时刻（UTC）；缺省取服务器当前时刻
     */
    public record ConfirmRequest(@NotBlank String stationCode, Instant confirmedAt) {
    }

    /**
     * 交接取消请求。
     *
     * @param stationCode 取消站点（必须为发起站点）
     * @param cancelledAt 取消时刻（UTC）；缺省取服务器当前时刻
     */
    public record CancelRequest(@NotBlank String stationCode, Instant cancelledAt) {
    }

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 发起交接：同 requestId 同参重放，异参 409。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView initiate(@Valid @RequestBody InitiateRequest request) {
        return transferService.initiate(request.requestId(), request.tagCode(),
                request.fromStation(), request.toStation(), request.initiatedAt());
    }

    /**
     * 目标站点确认交接，确认后持有权转移。
     */
    @PostMapping("/{id}/confirm")
    public TransferView confirm(@PathVariable long id,
            @Valid @RequestBody ConfirmRequest request) {
        return transferService.confirm(id, request.stationCode(), request.confirmedAt());
    }

    /**
     * 发起站点取消交接，仅待确认状态可取消。
     */
    @PostMapping("/{id}/cancel")
    public TransferView cancel(@PathVariable long id, @Valid @RequestBody CancelRequest request) {
        return transferService.cancel(id, request.stationCode(), request.cancelledAt());
    }
}