package com.example.starter.maintenance.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.CreateTemplateRequest;
import com.example.starter.maintenance.api.dto.ReviseTemplateRequest;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.service.EvidenceService;

/**
 * 保养模板 API：模板按版本固化，修订生成递增版本，历史版本不改写。
 */
@RestController
@RequestMapping("/api/maintenance-templates")
public class TemplateController {

    private final EvidenceService service;

    public TemplateController(EvidenceService service) {
        this.service = service;
    }

    /** 创建保养模板：初始版本 1，检查项编码不可重复。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest req) {
        return service.createTemplate(req);
    }

    /** 修订保养模板：整体替换检查项集合，版本加一；历史版本及旧周期证据不改写。 */
    @PostMapping("/{templateId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse revise(@PathVariable String templateId,
                                   @Valid @RequestBody ReviseTemplateRequest req) {
        return service.reviseTemplate(templateId, req);
    }

    /** 当前模板版本及检查项（规范排序）。 */
    @GetMapping("/{templateId}")
    public TemplateResponse get(@PathVariable String templateId) {
        return service.getTemplate(templateId);
    }
}
