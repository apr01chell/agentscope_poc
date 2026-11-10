package io.agentscope.platform.server.artifact;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * RustFS（S3 兼容）实现。
 *
 * <p>两个必须打开的开关：`forcePathStyle`（RustFS/MinIO 不支持 virtual-host 风格）
 * 与显式 endpoint（不能用 AWS 的域名）。
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStore(
            @Value("${app.oss.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.oss.access-key:rustfsadmin}") String accessKey,
            @Value("${app.oss.secret-key:rustfsadmin}") String secretKey,
            @Value("${app.oss.bucket:agentscope}") String bucket,
            @Value("${app.oss.region:us-east-1}") String region) {

        this.bucket = bucket;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var common = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(common)
                .build();

        // bucket 不存在时上传会 404，且错误信息不明显；启动时幂等建一个
        try {
            client.headBucket(b -> b.bucket(this.bucket));
        }
        catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException missing) {
            client.createBucket(b -> b.bucket(this.bucket));
            log.info("已创建对象存储 bucket: {}", this.bucket);
        }
        catch (RuntimeException ignored) {
            // 存储不可用不该拦住应用启动：产物功能会报错，但对话主流程可用
            log.warn("对象存储不可用（bucket 检查失败），产物功能将不可用");
        }

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(common)
                .build();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content));

        // 写完必须校验：上传"看起来成功"但对象不存在的情况真实存在（尤其对象存储网关）
        var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        if (head.contentLength() == null || head.contentLength() != content.length) {
            throw new ObjectStoreException(
                    "上传校验失败：期望 " + content.length + " 字节，实际 " + head.contentLength());
        }
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }

    @Override
    public InputStream get(String key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
