package com.example.starter.observation;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 圆弧三方合并冲突：圆弧集合整体是一个完整字段组，冲突时整组报 409（conflictFields 固定为 ["arcs"]）。
 *
 * <p>该异常在事务上配置为不回滚：抛出前已删除 requestId 占位（失败不占键），
 * 事务提交仅落库“未解决冲突”标记（conflict_pending），供后续裁定引用时判定 422。
 */
public class ArcMergeConflictException extends ApiException {

    public ArcMergeConflictException(String message, int currentVersion) {
        super(HttpStatus.CONFLICT, message, List.of("arcs"), currentVersion);
    }
}
