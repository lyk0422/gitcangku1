package com.example.starter.domain;

/**
 * 多边形环校验失败：环不闭合、退化、自交、孔洞不严格在外环内部或孔洞相交等。
 * 消息描述具体非法原因，由服务层转换为 HTTP 400。
 */
public class InvalidPolygonException extends IllegalArgumentException {

    public InvalidPolygonException(String message) {
        super(message);
    }
}
