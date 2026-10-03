package com.example.starter.aging.web;

import com.example.starter.aging.service.AgingService;
import com.example.starter.aging.web.ApiRequests.CreateBatchRequest;
import com.example.starter.aging.web.ApiRequests.ReleaseRequest;
import com.example.starter.aging.web.ApiRequests.TempRequest;
import com.example.starter.aging.web.ApiRequests.TurnRequest;
import com.example.starter.aging.web.ApiRequests.VoidRequest;
import com.example.starter.aging.web.ApiViews.BatchView;
import com.example.starter.aging.web.ApiViews.BoardView;
import com.example.starter.aging.web.ApiViews.ReleaseView;
import com.example.starter.aging.web.ApiViews.TempView;
import com.example.starter.aging.web.ApiViews.TurnView;
import com.example.starter.aging.web.ApiViews.VoidResultView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 熟成批次接口：批次、翻面、测温、作废、放行与看板查询。
 */
@RestController
@RequestMapping("/api")
public class AgingController {

    private final AgingService service;

    public AgingController(AgingService service) {
        this.service = service;
    }

    /** 创建熟成批次。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView createBatch(@Valid @RequestBody CreateBatchRequest req) {
        return service.createBatch(req.batchCode(), req.entryAt());
    }

    /** 确认某日翻面，同 requestId 同参幂等，异参 409。 */
    @PostMapping("/batches/{batchId}/turns")
    public TurnView confirmTurn(@PathVariable long batchId, @Valid @RequestBody TurnRequest req) {
        return service.confirmTurn(batchId, req.requestId(), req.turnDate(), req.operator(), req.note());
    }

    /** 追加测温记录。 */
    @PostMapping("/batches/{batchId}/temperatures")
    @ResponseStatus(HttpStatus.CREATED)
    public TempView addTemperature(@PathVariable long batchId, @Valid @RequestBody TempRequest req) {
        return service.addTemperature(batchId, req.tempDate(), req.celsius(), req.recordedBy());
    }

    /** 作废测温记录，可同事务追加替代记录。 */
    @PostMapping("/temperatures/{tempId}/void")
    public VoidResultView voidTemperature(@PathVariable long tempId, @Valid @RequestBody VoidRequest req) {
        var rep = req.replacement();
        return service.voidTemperature(tempId, req.reason(),
                rep == null ? null : rep.tempDate(),
                rep == null ? null : rep.celsius(),
                rep == null ? null : rep.recordedBy());
    }

    /** 质检放行，同 requestId 重放幂等，异参 409。 */
    @PostMapping("/batches/{batchId}/release")
    public ReleaseView release(@PathVariable long batchId, @Valid @RequestBody ReleaseRequest req) {
        return service.release(batchId, req.requestId());
    }

    /** 看板查询：日期轴、当日有效温度与放行引用。 */
    @GetMapping("/batches/{batchId}/board")
    public BoardView board(@PathVariable long batchId) {
        return service.board(batchId);
    }
}