package com.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "text2sql")
public class AppProperties {
    private String jwtSecret;
    private long jwtExpireMinutes;
    private String cryptoSecret;
    private String aiServiceUrl;

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtExpireMinutes() { return jwtExpireMinutes; }
    public void setJwtExpireMinutes(long jwtExpireMinutes) { this.jwtExpireMinutes = jwtExpireMinutes; }
    public String getCryptoSecret() { return cryptoSecret; }
    public void setCryptoSecret(String cryptoSecret) { this.cryptoSecret = cryptoSecret; }
    public String getAiServiceUrl() { return aiServiceUrl; }
    public void setAiServiceUrl(String aiServiceUrl) { this.aiServiceUrl = aiServiceUrl; }
}
