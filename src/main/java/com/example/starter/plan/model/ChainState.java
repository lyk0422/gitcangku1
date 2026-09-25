package com.example.starter.plan.model;

/**
 * 计划在车底交路链中的状态：NORMAL 正常衔接；PENDING_REPLAN 因前序段取消导致断链，
 * 被标记为待重排，待后续发布/改签/周转参数调整使链恢复连续后清除。
 */
public enum ChainState {
    NORMAL,
    PENDING_REPLAN
}
