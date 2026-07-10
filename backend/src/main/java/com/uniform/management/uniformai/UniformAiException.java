package com.uniform.management.uniformai;

public class UniformAiException extends RuntimeException {
    public UniformAiException(String message) {
        super(message);
    }

    public UniformAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
