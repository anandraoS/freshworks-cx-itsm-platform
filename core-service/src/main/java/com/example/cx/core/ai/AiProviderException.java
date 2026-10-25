package com.example.cx.core.ai;

/** Provider failure. {@code retryable} = worth another attempt (network blip, 5xx, injected test fault); false = give up now. */
public class AiProviderException extends RuntimeException {
    private final boolean retryable;

    public AiProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AiProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
