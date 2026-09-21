package com.example.starter.site;

import com.example.starter.site.dto.CommandRequest;
import com.example.starter.site.dto.CreateIsolationRequest;
import com.example.starter.site.dto.IsolationView;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 现场隔离记录接口：创建、拆除、单条与列表查询。
 */
@RestController
@RequestMapping("/api/isolations")
public class IsolationController {

    private final SiteService service;

    public IsolationController(SiteService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateIsolationRequest request) {
        return toResponse(service.createIsolation(request));
    }

    @PostMapping("/{isolationKey}/remove")
    public ResponseEntity<String> remove(@PathVariable String isolationKey,
                                         @Valid @RequestBody CommandRequest request) {
        return toResponse(service.removeIsolation(isolationKey, request.commandKey()));
    }

    @GetMapping("/{isolationKey}")
    public IsolationView get(@PathVariable String isolationKey) {
        return service.getIsolation(isolationKey);
    }

    @GetMapping
    public List<IsolationView> list(@RequestParam(required = false) String deviceId,
                                    @RequestParam(required = false) String status) {
        return service.listIsolations(deviceId, status);
    }

    static ResponseEntity<String> toResponse(SiteService.CommandOutcome outcome) {
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }
}
