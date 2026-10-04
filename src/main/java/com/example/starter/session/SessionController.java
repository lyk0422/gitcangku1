package com.example.starter.session;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.session.SessionDtos.CreateSessionRequest;
import com.example.starter.session.SessionDtos.ReportRequest;
import com.example.starter.session.SessionDtos.ReportView;
import com.example.starter.session.SessionDtos.SessionDetailView;
import com.example.starter.session.SessionDtos.SessionView;

/**
 * 排练场次接口：创建、重绑版本、开始冻结、按序回报、结束与结果查询。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@Valid @RequestBody CreateSessionRequest request) {
        return service.createSession(request);
    }

    @PostMapping("/{id}/rebind")
    public SessionView rebind(@PathVariable long id) {
        return service.rebind(id);
    }

    @PostMapping("/{id}/start")
    public SessionView start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/reports")
    public ReportView report(@PathVariable long id, @Valid @RequestBody ReportRequest request) {
        return service.report(id, request);
    }

    @PostMapping("/{id}/finish")
    public SessionView finish(@PathVariable long id) {
        return service.finish(id);
    }

    @GetMapping("/{id}")
    public SessionDetailView detail(@PathVariable long id) {
        return service.detail(id);
    }
}