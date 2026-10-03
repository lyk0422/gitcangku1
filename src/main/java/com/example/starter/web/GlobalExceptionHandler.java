package com.example.starter.web;

import com.example.starter.domain.ApiException;
import com.example.starter.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 鍏ㄥ眬寮傚父澶勭悊锛氫笟鍔″紓甯歌浆涓烘惡甯﹀師鍥犵爜鐨勯敊璇搷搴斻€? */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorResponse(ex.reason(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", "璇锋眰鍙傛暟鏍￠獙澶辫触"));
    }
}