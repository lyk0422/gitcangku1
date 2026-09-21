package com.example.starter.curtailment.commitment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容量承诺接口：创建、暂停、查询。
 */
@RestController
@RequestMapping("/api/commitments")
public class CommitmentController {

    private final CommitmentService service;

    public CommitmentController(CommitmentService service) {
        this.service = service;
    }

    @PostMapping
    public CommitmentResponse create(@RequestBody CreateCommitmentRequest request) {
        return service.create(request);
    }

    @PostMapping("/{commitmentKey}/suspend")
    public CommitmentResponse suspend(@PathVariable String commitmentKey,
                                      @RequestBody SuspendCommitmentRequest request) {
        return service.suspend(commitmentKey, request);
    }

    @GetMapping("/{commitmentKey}")
    public CommitmentResponse get(@PathVariable String commitmentKey) {
        return service.get(commitmentKey);
    }
}
