package com.aegistrace.storage;

import com.aegistrace.common.ApiException;
import com.aegistrace.config.AppProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;

@Component
public class ObjectStore {
    private final AppProperties.S3 config;
    private volatile S3Client client;

    public ObjectStore(AppProperties properties) {
        this.config = properties.getS3();
    }

    public String put(String key, byte[] bytes, String contentType) {
        if (config.getEndpoint() == null || config.getEndpoint().isBlank()) {
            throw new ApiException("DEPENDENCY_UNAVAILABLE", "Object storage is not configured.", 503);
        }
        try {
            client().putObject(PutObjectRequest.builder().bucket(config.getBucket()).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(bytes));
            return key;
        } catch (Exception ex) {
            throw new ApiException("DEPENDENCY_UNAVAILABLE", "Object storage rejected the upload.", 503);
        }
    }

    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    S3Client candidate = S3Client.builder()
                            .endpointOverride(URI.create(config.getEndpoint()))
                            .region(Region.of(config.getRegion()))
                            .forcePathStyle(true)
                            .overrideConfiguration(ClientOverrideConfiguration.builder()
                                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                                    .apiCallTimeout(Duration.ofSeconds(12))
                                    .retryPolicy(RetryPolicy.builder().numRetries(2).build())
                                    .build())
                            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                            .build();
                    try {
                        candidate.headBucket(HeadBucketRequest.builder().bucket(config.getBucket()).build());
                    } catch (S3Exception ex) {
                        if (ex.statusCode() != 404) {
                            candidate.close();
                            throw ex;
                        }
                        candidate.createBucket(CreateBucketRequest.builder().bucket(config.getBucket()).build());
                    }
                    client = candidate;
                }
            }
        }
        return client;
    }
}
