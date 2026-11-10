package io.agentscope.platform.server.artifact;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储。业务只依赖这个接口，S3 / 其它实现可替换。
 *
 * <p>key 一律由服务端生成（见 {@link ArtifactService}），绝不让沙箱或模型决定落点——
 * 沙箱里跑着模型生成的脚本，否则可以伪造任意路径。
 */
public interface ObjectStore {

    /** 上传并**校验**（HEAD 确认存在且大小一致）；校验失败抛异常，不返回到调用方。 */
    void put(String key, byte[] content, String contentType);

    /** 生成有时效的下载地址。 */
    String presignedGetUrl(String key, Duration ttl);

    InputStream get(String key);
}
