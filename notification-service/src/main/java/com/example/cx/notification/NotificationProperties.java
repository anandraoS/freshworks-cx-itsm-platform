package com.example.cx.notification;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code cx.notifications.*} */
@ConfigurationProperties(prefix = "cx.notifications")
public class NotificationProperties {
    /** Library mode inside core-service (auto-registers the module). */
    private boolean embedded = false;
    private List<NotificationChannel> channels = List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
    /** Max processing attempts (first try included) before dead-lettering. */
    private int maxAttempts = 5;
    private long retryDelayMs = 5000;
    /** 1.0 = fixed delay served by the TTL retry queue; greater than 1 uses per-message expiration (head-of-line caveat). */
    private double backoffMultiplier = 1.0;
    private long maxRetryDelayMs = 60000;
    /** Delay between in-process retries (local mode). */
    private long localRetryDelayMs = 0;
    /** Verify that recipients belong to the event tenant (uses core-service app_user when reachable; payload tenant check always applies). */
    private boolean validateRecipients = true;
    private final Rabbit rabbit = new Rabbit();

    public static class Rabbit {
        private boolean enabled = false;
        private int prefetch = 10;
        private int concurrency = 2;
        private int maxConcurrency = 4;
        private long confirmTimeoutMs = 5000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPrefetch() { return prefetch; }
        public void setPrefetch(int prefetch) { this.prefetch = prefetch; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public long getConfirmTimeoutMs() { return confirmTimeoutMs; }
        public void setConfirmTimeoutMs(long confirmTimeoutMs) { this.confirmTimeoutMs = confirmTimeoutMs; }
    }

    public boolean isEmbedded() { return embedded; }
    public void setEmbedded(boolean embedded) { this.embedded = embedded; }
    public List<NotificationChannel> getChannels() { return channels; }
    public void setChannels(List<NotificationChannel> channels) { this.channels = channels; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public long getMaxRetryDelayMs() { return maxRetryDelayMs; }
    public void setMaxRetryDelayMs(long maxRetryDelayMs) { this.maxRetryDelayMs = maxRetryDelayMs; }
    public long getLocalRetryDelayMs() { return localRetryDelayMs; }
    public void setLocalRetryDelayMs(long localRetryDelayMs) { this.localRetryDelayMs = localRetryDelayMs; }
    public boolean isValidateRecipients() { return validateRecipients; }
    public void setValidateRecipients(boolean validateRecipients) { this.validateRecipients = validateRecipients; }
    public Rabbit getRabbit() { return rabbit; }
}
