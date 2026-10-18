package com.chargehub.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ExceptionAdvice {
    private static final Logger LOG = LoggerFactory.getLogger(ExceptionAdvice.class);
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse> business(BusinessException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(403).body(ApiResponse.error("FORBIDDEN", "无权执行此操作"));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.ServletRequestBindingException.class})
    ResponseEntity<ApiResponse> invalid(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "请求字段或格式不正确"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(ApiResponse.error("STATE_CONFLICT", "资源状态或唯一约束冲突"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse> unexpected(Exception e) {
        LOG.error("Unhandled request error [{}]", ApiResponse.requestIdValue(), e);
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用"));
    }
}
