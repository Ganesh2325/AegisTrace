package com.aegistrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegis")
public class AppProperties {
    private String jwtSecret;
    private String internalToken;
    private String runtimeUrl;
    private boolean seedEnabled;
    private String seedPassword;
    private String environment = "dev";
    private boolean failureSimulationEnabled;
    private String frontendOrigin = "http://localhost:3000";
    private boolean cookieSecure;
    private Redis redis = new Redis();
    private S3 s3 = new S3();
    private String jaegerQueryUrl = "";
    private String jaegerPublicUrl = "http://localhost:16686";
    private String prometheusUrl = "";
    private String releaseSha = "";
    private String imageDigest = "";

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    public String getRuntimeUrl() { return runtimeUrl; }
    public void setRuntimeUrl(String runtimeUrl) { this.runtimeUrl = runtimeUrl; }
    public boolean isSeedEnabled() { return seedEnabled; }
    public void setSeedEnabled(boolean seedEnabled) { this.seedEnabled = seedEnabled; }
    public String getSeedPassword() { return seedPassword; }
    public void setSeedPassword(String seedPassword) { this.seedPassword = seedPassword; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public boolean isFailureSimulationEnabled() { return failureSimulationEnabled; }
    public void setFailureSimulationEnabled(boolean failureSimulationEnabled) { this.failureSimulationEnabled = failureSimulationEnabled; }
    public String getFrontendOrigin() { return frontendOrigin; }
    public void setFrontendOrigin(String frontendOrigin) { this.frontendOrigin = frontendOrigin; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }
    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }
    public String getJaegerQueryUrl() { return jaegerQueryUrl; }
    public void setJaegerQueryUrl(String jaegerQueryUrl) { this.jaegerQueryUrl = jaegerQueryUrl; }
    public String getJaegerPublicUrl() { return jaegerPublicUrl; }
    public void setJaegerPublicUrl(String jaegerPublicUrl) { this.jaegerPublicUrl = jaegerPublicUrl; }
    public String getPrometheusUrl() { return prometheusUrl; }
    public void setPrometheusUrl(String prometheusUrl) { this.prometheusUrl = prometheusUrl; }
    public String getReleaseSha() { return releaseSha; }
    public void setReleaseSha(String releaseSha) { this.releaseSha = releaseSha; }
    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }

    public boolean isProduction() {
        return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
    }

    public static class Redis {
        private boolean enabled;
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class S3 {
        private String endpoint = "";
        private String region = "us-east-1";
        private String bucket = "aegistrace-documents";
        private String accessKey = "";
        private String secretKey = "";
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }
}
