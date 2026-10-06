package com.example.starter.calibration.model;

/**
 * 读数变化方向：UP=上升，DOWN=下降。会话首条显式指定，后续按原值比较推导，
 * 相同则继承前一条方向；会话关闭后全部方向冻结。
 */
public enum Direction {
    UP,
    DOWN
}
