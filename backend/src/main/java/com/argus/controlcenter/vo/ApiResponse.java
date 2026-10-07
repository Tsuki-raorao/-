package com.argus.controlcenter.vo;

/** 所有 REST 接口共用的响应外壳，便于前端统一处理错误和数据。 */
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 创建成功响应。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 创建资源成功响应。 */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(0, "created", data);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
