package com.example.starter.site;

import com.example.starter.site.dto.CommandRequest;
import com.example.starter.site.dto.CreatePermitRequest;
import com.example.starter.site.dto.PermitView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 作业许可接口：创建、批准、关闭、生效许可与历史明细查询。
 * 批准与关闭的操作人通过 X-Actor-Id 请求头提供。
 */
@RestController
@RequestMapping("/api/permits")
public class PermitController {

    private final SiteService service;

    public PermitController(SiteService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreatePermitRequest request) {
        return IsolationController.toResponse(service.createPermit(request));
    }

    @PostMapping("/{permitKey}/approvals")
    public ResponseEntity<String> approve(@PathVariable String permitKey,
                                          @RequestHeader("X-Actor-Id") @NotBlank String actor,
                                          @Valid @RequestBody CommandRequest request) {
        return IsolationController.toResponse(service.approvePermit(permitKey, actor, request.commandKey()));
    }

    @PostMapping("/{permitKey}/close")
    public ResponseEntity<String> close(@PathVariable String permitKey,
                                        @RequestHeader("X-Actor-Id") @NotBlank String actor,
                                        @Valid @RequestBody CommandRequest request) {
        return IsolationController.toResponse(service.closePermit(permitKey, actor, request.commandKey()));
    }

    @GetMapping("/effective")
    public List<PermitView> effective() {
        return service.listEffectivePermits();
    }

    @GetMapping("/{permitKey}")
    public PermitView get(@PathVariable String permitKey) {
        return service.getPermit(permitKey);
    }

    @GetMapping
    public List<PermitView> list(@RequestParam(required = false) String status) {
        return service.listPermits(status);
    }
}
