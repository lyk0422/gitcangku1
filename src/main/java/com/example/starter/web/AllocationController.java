package com.example.starter.web;

import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.ApproveRequest;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.TimelineView;
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
 * 观测窗口申请、批准、撤销与查询的本地业务入口。
 */
@RestController
@RequestMapping("/api/requests")
public class AllocationController {

    private final AllocationControllerDelegate delegate;

    public AllocationController(AllocationControllerDelegate delegate) {
        this.delegate = delegate;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView apply(@Valid @RequestBody ApplyRequest request) {
        return delegate.apply(request);
    }

    @PostMapping("/{requestNo}/approval")
    public RequestView approve(@PathVariable String requestNo,
                               @RequestBody(required = false) ApproveRequest body) {
        return delegate.approve(requestNo, body == null ? null : body.requestId());
    }

    @PostMapping("/{requestNo}/cancellation")
    public RequestView cancel(@PathVariable String requestNo,
                              @Valid @RequestBody CancelRequest body) {
        return delegate.cancel(requestNo, body);
    }

    @GetMapping("/{requestNo}")
    public RequestView getRequest(@PathVariable String requestNo) {
        return delegate.getRequest(requestNo);
    }

    @GetMapping
    public TimelineView timeline(@org.springframework.web.bind.annotation.RequestParam String instrumentId) {
        return delegate.getTimeline(instrumentId);
    }
}
