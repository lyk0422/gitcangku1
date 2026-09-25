package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.PublishBlockDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 发布被业务门禁阻断时抛出的 422 异常：携带稳定排序的区域与窗口明细，
 * 由全局异常处理器原样返回，保证调用方一次响应即可获得全部阻断原因。
 */
public class PublishBlockedException extends ApiException {

    private final transient List<PublishBlockDetail> items;

    public PublishBlockedException(String code, String message, List<PublishBlockDetail> items) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        this.items = List.copyOf(items);
    }

    public List<PublishBlockDetail> items() {
        return items;
    }
}
