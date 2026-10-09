package com.argus.controlcenter.identity;

import com.argus.controlcenter.vo.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 身份错误使用稳定码，避免把 SQL、OIDC 或凭据细节泄漏给调用方。 */
@RestControllerAdvice
public class IdentityExceptionHandler {
    @ExceptionHandler(IdentityAuthorizationException.class)
    public ResponseEntity<ApiResponse<Void>> handle(IdentityAuthorizationException e) {
        return ResponseEntity.status(e.status()).body(new ApiResponse<>(e.status(), e.code(), null));
    }
}
