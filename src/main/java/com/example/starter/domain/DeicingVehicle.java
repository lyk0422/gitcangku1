package com.example.starter.domain;

/** 除冰车，一辆车同一时刻只服务一个航班。 */
public record DeicingVehicle(long id, String code, String name) {
}