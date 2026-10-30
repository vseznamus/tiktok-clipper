package ru.tiktokclipper.core;

/** Ошибка с HTTP-кодом и понятным пользователю текстом. */
public class ApiError extends Exception {
    public final int code;

    public ApiError(int code, String message) {
        super(message);
        this.code = code;
    }
}
