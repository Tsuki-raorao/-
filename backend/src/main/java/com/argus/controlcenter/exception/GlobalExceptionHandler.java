package com.argus.controlcenter.exception;

import com.argus.controlcenter.vo.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

/** 将领域异常转换为前端可识别的统一响应，避免泄露内部堆栈和凭据。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> notFound(NotFoundException e) { return new ApiResponseBuilder<Void>().error(404, e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream().map(x -> x.getField()+": "+x.getDefaultMessage()).collect(Collectors.joining(", "));
        return new ApiResponseBuilder<Void>().error(400, msg);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException e) { return new ApiResponseBuilder<Void>().error(400, e.getMessage()); }
    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<ApiResponse<Void>> agentGateway(AgentGatewayException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiResponseBuilder<Void>().error(e.getStatus(), e.getMessage()));
    }
    private static final class ApiResponseBuilder<T> {
        ApiResponse<T> error(int code, String message) { return new ApiResponse<>(code, message, null); }
    }
}
