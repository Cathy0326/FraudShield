package com.fraudshield.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 全局异常处理 — 统一JSON错误格式
 * Centralized error handler: converts all exceptions to a consistent JSON body.
 *
 * 为什么需要它 (Why this matters):
 *   没有它 → Spring默认返回HTML错误页 → 前端解析崩溃
 *   有了它 → 所有错误都是 {"error":...,"message":...,"timestamp":...}
 *   前端只需处理一种格式，开发体验大幅提升
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("Not Found", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody("Bad Request", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("Internal Server Error", ex.getMessage()));
    }

    private Map<String, Object> errorBody(String error, String message) {
        return Map.of(
                "error",     error,
                "message",   message != null ? message : "An unexpected error occurred",
                "timestamp", LocalDateTime.now().toString()
        );
    }
}
