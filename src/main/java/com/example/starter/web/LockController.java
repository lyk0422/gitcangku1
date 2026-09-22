package com.example.starter.web;

import com.example.starter.service.LockService;
import com.example.starter.web.dto.CreateLockRequest;
import com.example.starter.web.dto.LockFileResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * 依赖锁定与历史锁文件查询接口。
 */
@RestController
@RequestMapping("/api/locks")
public class LockController {

    private final LockService lockService;

    public LockController(LockService lockService) {
        this.lockService = lockService;
    }

    /**
     * 以精确根版本创建锁文件；仓库版本不符409，无可行组合422。
     */
    @PostMapping
    public ResponseEntity<LockFileResponse> create(@Valid @RequestBody CreateLockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(lockService.createLock(request));
    }

    /**
     * 按ID查询历史锁文件；制品后续撤回不影响查询结果。
     */
    @GetMapping("/{id}")
    public LockFileResponse get(@PathVariable long id) {
        return lockService.getLock(id);
    }

    /**
     * 查询锁文件列表，可按根名称过滤；明细按名称排序。
     */
    @GetMapping
    public List<LockFileResponse> list(@RequestParam(required = false) String rootName) {
        return lockService.listLocks(rootName);
    }
}
