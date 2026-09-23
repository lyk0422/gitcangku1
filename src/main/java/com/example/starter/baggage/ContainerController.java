package com.example.starter.baggage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.baggage.ContainerDtos.ContainerChainResponse;
import com.example.starter.baggage.ContainerDtos.ContainerLoadRequest;
import com.example.starter.baggage.ContainerDtos.ContainerResponse;
import com.example.starter.baggage.ContainerDtos.ContainerSealRequest;
import com.example.starter.baggage.ContainerDtos.CreateContainerRequest;

/**
 * 行李容器 REST 入口：创建、装箱、封签与查询。
 */
@RestController
@RequestMapping("/api")
public class ContainerController {

    private final ContainerService containerService;

    public ContainerController(ContainerService containerService) {
        this.containerService = containerService;
    }

    /** 创建容器：绑定同一航段与同一交接点。 */
    @PostMapping("/containers")
    public ResponseEntity<ContainerResponse> createContainer(
            @Valid @RequestBody CreateContainerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(containerService.createContainer(request));
    }

    /** 容器装箱：整批原子，任一行李不满足则 422 且无一件入箱。 */
    @PostMapping("/containers/{containerId}/load")
    public ContainerResponse load(@PathVariable String containerId,
                                  @Valid @RequestBody ContainerLoadRequest request) {
        return containerService.load(containerId, request);
    }

    /** 容器封签：校验版本，sealNo 全局唯一，成功后转 SEALED。 */
    @PostMapping("/containers/{containerId}/seal")
    public ContainerResponse seal(@PathVariable String containerId,
                                  @Valid @RequestBody ContainerSealRequest request) {
        return containerService.seal(containerId, request);
    }

    /** 容器详情查询：含当前清单。 */
    @GetMapping("/containers/{containerId}")
    public ContainerResponse getContainer(@PathVariable String containerId) {
        return containerService.getContainer(containerId);
    }

    /** 行李容器链查询：逐项保留行李经过的全部容器。 */
    @GetMapping("/bags/{bagTag}/container-chain")
    public ContainerChainResponse getBagChain(@PathVariable String bagTag) {
        return containerService.getBagChain(bagTag);
    }
}
