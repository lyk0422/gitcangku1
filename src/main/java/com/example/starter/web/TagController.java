package com.example.starter.web;

import com.example.starter.service.TagService;
import com.example.starter.service.TagService.TagView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签登记与持有状态查询。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    /**
     * 标签登记请求。
     *
     * @param tagCode 标签编号（合成）
     * @param stationCode 初始持有站点编号
     */
    public record RegisterRequest(@NotBlank String tagCode, @NotBlank String stationCode) {
    }

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 登记标签，初始持有站点为登记站点。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagView register(@Valid @RequestBody RegisterRequest request) {
        return tagService.register(request.tagCode(), request.stationCode());
    }

    /**
     * 查询标签当前持有站点。
     */
    @GetMapping("/{tagCode}")
    public TagView get(@PathVariable String tagCode) {
        return tagService.get(tagCode);
    }
}