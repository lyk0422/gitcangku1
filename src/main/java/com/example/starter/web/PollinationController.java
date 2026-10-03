package com.example.starter.web;

import com.example.starter.service.PollinationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授粉操作的撤销与结果确认接口。
 */
@RestController
@RequestMapping("/api/pollinations")
public class PollinationController {

    private final PollinationService service;

    public PollinationController(PollinationService service) {
        this.service = service;
    }

    /** 撤销尚未确认的操作；已确认成功的操作返回 409。 */
    @PostMapping("/{id}/revocation")
    public OperationView revoke(@PathVariable long id) {
        return service.revokeOperation(id);
    }

    /** 确认授粉结果（幂等），须在执行后 72 小时内。 */
    @PostMapping("/{id}/confirmation")
    public OperationView confirm(@PathVariable long id, @Valid @RequestBody ConfirmOperationRequest request) {
        return service.confirmOperation(id, request);
    }
}