package com.example.starter.plan.web;

import com.example.starter.plan.service.RollingStockService;
import com.example.starter.plan.web.dto.ChainBreakView;
import com.example.starter.plan.web.dto.CreateRollingStockRequest;
import com.example.starter.plan.web.dto.RollingStockResponse;
import com.example.starter.plan.web.dto.StockChainResponse;
import com.example.starter.plan.web.dto.UpdateTurnaroundRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车底 API：登记、最小周转参数修改（携带 expectedVersion 并重校验全链）、
 * 按车底的交路链明细与断链记录查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/rolling-stocks")
public class RollingStockController {

    private final RollingStockService service;

    public RollingStockController(RollingStockService service) {
        this.service = service;
    }

    /**
     * 登记车底与最小周转分钟数（1～240）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RollingStockResponse create(@Valid @RequestBody CreateRollingStockRequest request) {
        return service.createStock(request);
    }

    /**
     * 修改最小周转分钟数：expectedVersion 乐观校验，成功后重校验该车底全部已发布相邻段。
     */
    @PutMapping("/{stockKey}/turnaround")
    public RollingStockResponse updateTurnaround(@PathVariable String stockKey,
                                                 @Valid @RequestBody UpdateTurnaroundRequest request) {
        return service.updateTurnaround(stockKey, request);
    }

    /**
     * 按车底与运营日查询交路链明细（含逐段衔接评估）。
     */
    @GetMapping("/{stockKey}/chain")
    public StockChainResponse getChain(
            @PathVariable String stockKey,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getChain(stockKey, date);
    }

    /**
     * 按车底查询不可变断链记录。
     */
    @GetMapping("/{stockKey}/breaks")
    public List<ChainBreakView> getBreaks(@PathVariable String stockKey) {
        return service.getBreaks(stockKey);
    }
}
