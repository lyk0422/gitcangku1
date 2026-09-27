package com.example.starter.plan.web.dto;

/**
 * 危险品编组车厢视图，按计划内车厢位置升序返回。
 */
public record CarView(int carPosition, String category) {
}
