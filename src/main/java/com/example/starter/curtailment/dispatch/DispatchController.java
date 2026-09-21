package com.example.starter.curtailment.dispatch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 削减调度接口：创建草稿、替换分配、发布、取消、当前已发布查询、详情与历史。
 */
@RestController
@RequestMapping("/api/dispatches")
public class DispatchController {

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @PostMapping
    public DispatchResponse create(@RequestBody CreateDispatchRequest request) {
        return service.create(request);
    }

    @PutMapping("/{dispatchKey}/allocations")
    public DispatchResponse replaceAllocations(@PathVariable String dispatchKey,
                                               @RequestBody ReplaceAllocationsRequest request) {
        return service.replaceAllocations(dispatchKey, request);
    }

    @PostMapping("/{dispatchKey}/publish")
    public DispatchResponse publish(@PathVariable String dispatchKey, @RequestBody CommandRequest request) {
        return service.publish(dispatchKey, request);
    }

    @PostMapping("/{dispatchKey}/cancel")
    public DispatchResponse cancel(@PathVariable String dispatchKey, @RequestBody CommandRequest request) {
        return service.cancel(dispatchKey, request);
    }

    @GetMapping("/published")
    public List<DispatchResponse> listPublished(@RequestParam(required = false) String feederId) {
        return service.listPublished(feederId);
    }

    @GetMapping("/{dispatchKey}")
    public DispatchResponse get(@PathVariable String dispatchKey) {
        return service.get(dispatchKey);
    }

    @GetMapping("/{dispatchKey}/history")
    public List<DispatchEventView> history(@PathVariable String dispatchKey) {
        return service.history(dispatchKey);
    }
}
