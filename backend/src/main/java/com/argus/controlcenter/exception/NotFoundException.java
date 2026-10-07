package com.argus.controlcenter.exception;

/** 领域对象不存在时使用的业务异常。 */
public class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
