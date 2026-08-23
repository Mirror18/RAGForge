package com.ragforge.server.ingestion;

import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.provider.SpaceBindingRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Fetches a user-approved public page and submits it through the normal ingestion pipeline. */
@Service
public class WebSourceIngestionService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "text/plain", "text/markdown");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private final HttpClient http;
    private final SpaceBindingRepository bindings;
    private final BusinessIngestionService ingestion;
    private final AuditOutboxService outbox;
    private final Set<String> allowedHosts;

    public WebSourceIngestionService(SpaceBindingRepository bindings, BusinessIngestionService ingestion,
                                     AuditOutboxService outbox,
                                     @Value("${ragforge.web-source.allowed-hosts:}") String allowedHosts) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.bindings = bindings;
        this.ingestion = ingestion;
        this.outbox = outbox;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public BusinessIngestionService.UploadView ingest(UUID spaceId, WebSourceRequest source,
                                                      String idempotencyKey, SessionPrincipal principal,
                                                      HttpServletRequest request) {
        if (source == null || source.url() == null || source.url().isBlank()) {
            throw invalid("url", "网页 URL 不能为空");
        }
        if (!source.allowCloudEgress()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "web_egress_not_approved", "Web egress not approved",
                    "加载网页知识源前必须明确确认云端出境");
        }
        var binding = bindings.findCurrent(spaceId).orElseThrow(() -> invalid("space", "当前空间没有绑定配置"));
        if (!binding.cloudEgressEnabled() || binding.authorization() == null
                || binding.authorization().expiresAt().isBefore(Instant.now())
                || !("CHAT".equalsIgnoreCase(binding.authorization().scope())
                || "ALL".equalsIgnoreCase(binding.authorization().scope()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "web_egress_not_approved", "Web egress not approved",
                    "当前空间没有有效的云端出境授权");
        }
        URI uri = parse(source.url());
        requirePublicAllowlistedHost(uri);
        HttpResponse<byte[]> response;
        try {
            response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15)).header("Accept", "text/html,application/xhtml+xml,text/plain,text/markdown")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "web_source_unavailable", "Web source unavailable",
                    "网页暂时无法访问，请检查 URL、域名白名单和目标站点状态");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "web_source_status", "Web source unavailable",
                    "网页返回了不可用的 HTTP 状态");
        }
        if (response.body().length == 0 || response.body().length > MAX_BYTES) {
            throw invalid("url", "网页响应必须在 10 MiB 以内且不能为空");
        }
        String mediaType = response.headers().firstValue("content-type")
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)).orElse("text/html");
        if (!ALLOWED_MEDIA_TYPES.contains(mediaType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "web_source_media_type", "Unsupported web source",
                    "网页只支持 HTML、XHTML、纯文本和 Markdown");
        }
        String text = new String(response.body(), StandardCharsets.UTF_8);
        String markdown = mediaType.contains("html") ? htmlToMarkdown(text) : text;
        if (markdown.isBlank()) throw invalid("url", "网页没有可摄取的文本内容");
        String displayName = uri.getHost() + (extractTitle(text).isBlank() ? "" : " · " + extractTitle(text));
        String canonicalPath = "web-" + sha256(uri.toString()).substring(0, 24) + ".md";
        var result = ingestion.ingestFetched(spaceId, displayName, canonicalPath, "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8), idempotencyKey, principal, request, uri.toString());
        outbox.record("source.web.fetched.v1", principal.userId(), spaceId, result.jobId(),
                UUID.fromString(com.ragforge.server.common.CorrelationIdFilter.current(request)),
                java.util.Map.of("jobId", result.jobId(), "sourceId", result.sourceId(), "host", uri.getHost(),
                        "contentSha256", sha256(markdown.getBytes(StandardCharsets.UTF_8))));
        return result;
    }

    private URI parse(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw invalid("url", "仅支持带主机名的 HTTP/HTTPS URL，不能包含用户信息或片段");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw invalid("url", "URL 格式无效");
        }
    }

    private void requirePublicAllowlistedHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts.isEmpty() || allowedHosts.stream().noneMatch(item -> host.equals(item) || host.endsWith("." + item))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "web_source_not_allowlisted", "Web source not allowlisted",
                    "目标域名不在服务端白名单中");
        }
        try {
            if (Arrays.stream(InetAddress.getAllByName(host)).anyMatch(WebSourceIngestionService::isBlockedAddress)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "web_source_address_blocked", "Web source blocked",
                        "目标地址属于本机、内网或保留地址");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "web_source_dns_failed", "Web source unavailable",
                    "目标域名无法解析");
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress();
    }

    static String htmlToMarkdown(String value) {
        String withoutUnsafe = SCRIPT_STYLE.matcher(value).replaceAll(" ");
        String plain = TAG.matcher(withoutUnsafe).replaceAll(" ");
        return plain.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replaceAll("[ \\t\\r]+", " ")
                .replaceAll("\\n[ \\t]+", "\\n").trim();
    }

    static String extractTitle(String value) {
        var matcher = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>").matcher(value);
        return matcher.find() ? TAG.matcher(matcher.group(1)).replaceAll(" ").trim().replaceAll("\\s+", " ") : "";
    }

    private static String sha256(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static ApiException invalid(String field, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Validation failed", field + ": " + detail);
    }

    public record WebSourceRequest(String url, boolean allowCloudEgress) { }
}
