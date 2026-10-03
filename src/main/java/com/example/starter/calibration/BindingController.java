package com.example.starter.calibration;

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
 * 任务绑定接口。
 */
@RestController
@RequestMapping("/api/bindings")
public class BindingController {

    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 绑定任务到校准；幂等键同参重放返回原绑定。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BindingView bind(@Valid @RequestBody CreateBindingRequest request) {
        return bindingService.bind(request);
    }

    /** 按幂等键查询绑定。 */
    @GetMapping("/{requestId}")
    public BindingView getByRequestId(@PathVariable String requestId) {
        return bindingService.getByRequestId(requestId);
    }
}