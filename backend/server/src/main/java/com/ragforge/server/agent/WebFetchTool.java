package com.ragforge.server.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Allowlist-first web fetcher. The HTTP adapter must not follow redirects and
 * must bind the connection to the addresses returned by the resolver.
 */
public final class WebFetchTool {
    public interface Resolver {
        List<InetAddress> resolve(String host);
    }

    public interface Client {
        HttpResponse execute(URI uri, List<InetAddress> resolvedAddresses, Duration timeout,
                             long maxResponseBytes);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WebFetchRequest(UUID spaceId, String scheme, String host, Integer port, String path) {
        public WebFetchRequest {
            Objects.requireNonNull(spaceId, "spaceId");
            scheme = normalizeScheme(scheme);
            host = normalizeHost(host);
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("explicit port is required");
            }
            if (path == null || path.isBlank() || path.length() > 2048 || !path.startsWith("/")
                    || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                    || path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)
                    || path.matches(".*(^|/)\\.\\.?(/|$).*")
                    || path.matches("(?i).*%(2f|5c|2e).*")) {
                throw new IllegalArgumentException("path is invalid");
            }
            path = canonicalPath(path);
        }

        public URI uri() {
            try {
                return new URI(scheme, null, host, port, path, null, null);
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("request URI is invalid", exception);
            }
        }
    }

    public record WebEndpointAllowlist(String scheme, String host, int port, String pathPrefix) {
        public WebEndpointAllowlist {
            scheme = normalizeScheme(scheme);
            host = normalizeHost(host);
            if (port < 1 || port > 65535 || pathPrefix == null || !pathPrefix.startsWith("/")
                    || pathPrefix.indexOf('\\') >= 0 || pathPrefix.matches("(?i).*%(2f|5c|2e).*")) {
                throw new IllegalArgumentException("allowlist entry is invalid");
            }
            pathPrefix = canonicalPath(pathPrefix);
        }

        boolean allows(URI uri) {
            return scheme.equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(normalizeHost(uri.getHost()))
                    && port == effectivePort(uri) && pathAllowed(pathPrefix, canonicalPath(uri.getPath()));
        }
    }

    public record WebFetchPolicy(Set<WebEndpointAllowlist> allowlist, boolean externalEgressAllowed,
                                 long maxResponseBytes, Set<String> allowedMimeTypes,
                                 Duration timeout, int maxRedirects, int maxDownloads) {
        public WebFetchPolicy {
            allowlist = allowlist == null ? Set.of() : Set.copyOf(allowlist);
            allowedMimeTypes = allowedMimeTypes == null ? Set.of() : allowedMimeTypes.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024
                    || timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0
                    || maxRedirects < 0 || maxRedirects > 5 || maxDownloads < 1 || maxDownloads > 3
                    || allowlist.isEmpty() || allowedMimeTypes.isEmpty()) {
                throw new IllegalArgumentException("web policy is invalid");
            }
        }
    }

    public record HttpResponse(int status, String contentType, long contentLength,
                               byte[] body, String location) {
        public HttpResponse {
            if (status < 100 || status > 599 || contentLength < -1 || body == null) {
                throw new IllegalArgumentException("HTTP response is invalid");
            }
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    public record WebFetchResult(UUID spaceId, URI finalUri, int status, String mediaType,
                                 byte[] body, String bodyHash, boolean untrustedData,
                                 int redirectCount, int downloadCount) {
        public WebFetchResult {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(finalUri, "finalUri");
            if (status < 200 || status >= 300 || mediaType == null || body == null
                    || bodyHash == null || !bodyHash.matches("[0-9a-f]{64}") || !untrustedData
                    || redirectCount < 0 || downloadCount < 1) {
                throw new IllegalArgumentException("web result is invalid");
            }
            body = body.clone();
            if (!bodyHash.equals(AgentToolHashing.sha256(body))) {
                throw new IllegalArgumentException("web body hash mismatch");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private final AgentToolAuthorization authorization;
    private final Resolver resolver;
    private final Client client;
    private final ToolAuditRecorder audit;

    public WebFetchTool(AgentToolAuthorization authorization, Resolver resolver, Client client,
                        ToolAuditRecorder audit) {
        this.authorization = authorization;
        this.resolver = resolver;
        this.client = client;
        this.audit = audit;
    }

    public WebFetchResult execute(WebFetchRequest request, ToolExecutionContext context, WebFetchPolicy policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Map<String, String> safe = Map.of("scheme", request.scheme(), "hostHash", AgentToolHashing.sha256(request.host()),
                "port", Integer.toString(request.port()), "pathHash", AgentToolHashing.sha256(request.path()));
        try {
            authorization.requireRead(request.spaceId(), context);
            if (!policy.externalEgressAllowed()) {
                throw new AgentToolSecurityException("WEB_EGRESS_NOT_APPROVED");
            }
            URI current = request.uri();
            int redirects = 0;
            int downloads = 0;
            while (true) {
                requireAllowed(current, policy);
                List<InetAddress> addresses = resolveAndValidate(current.getHost());
                HttpResponse response = client.execute(current, addresses, policy.timeout(), policy.maxResponseBytes());
                if (response.contentLength() > policy.maxResponseBytes()
                        || response.body().length > policy.maxResponseBytes()) {
                    throw new AgentToolSecurityException("WEB_RESPONSE_TOO_LARGE");
                }
                if (isRedirect(response.status())) {
                    if (++redirects > policy.maxRedirects() || response.location() == null
                            || response.location().length() > 2048) {
                        throw new AgentToolSecurityException("WEB_REDIRECT_LIMIT");
                    }
                    current = redirect(current, response.location());
                    continue;
                }
                if (++downloads > policy.maxDownloads()) {
                    throw new AgentToolSecurityException("WEB_DOWNLOAD_LIMIT");
                }
                if (response.status() < 200 || response.status() >= 300) {
                    throw new AgentToolSecurityException("WEB_HTTP_STATUS");
                }
                String mediaType = mediaType(response.contentType());
                if (!policy.allowedMimeTypes().contains(mediaType)) {
                    throw new AgentToolSecurityException("WEB_MIME_NOT_ALLOWED");
                }
                byte[] body = response.body();
                WebFetchResult result = new WebFetchResult(request.spaceId(), current, response.status(),
                        mediaType, body, AgentToolHashing.sha256(body), true, redirects, downloads);
                audit.record(ToolAuditProjection.authorized(AgentToolName.WEB_FETCH, context, Instant.now(),
                        result.bodyHash(), null, safe));
                return result;
            }
        } catch (AgentToolSecurityException exception) {
            auditDenied(context, exception.errorCode(), safe);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(ToolAuditProjection.authorized(AgentToolName.WEB_FETCH, context, Instant.now(),
                    null, "TOOL_EXECUTION_FAILED", safe));
            throw exception;
        }
    }

    private static void requireAllowed(URI uri, WebFetchPolicy policy) {
        if (uri.getScheme() == null || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))
                || uri.getHost() == null || uri.getPort() <= 0 || uri.getUserInfo() != null || uri.getFragment() != null
                || policy.allowlist().stream().noneMatch(rule -> rule.allows(uri))) {
            throw new AgentToolSecurityException("WEB_ENDPOINT_NOT_ALLOWLISTED");
        }
    }

    private List<InetAddress> resolveAndValidate(String host) {
        List<InetAddress> addresses = resolver.resolve(host);
        if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(WebFetchTool::isBlockedAddress)) {
            throw new AgentToolSecurityException("WEB_ADDRESS_BLOCKED");
        }
        return List.copyOf(addresses);
    }

    private static URI redirect(URI base, String location) {
        try {
            URI target = base.resolve(new URI(location));
            if (target.getRawAuthority() != null && target.getRawAuthority().contains("@")) {
                throw new AgentToolSecurityException("WEB_REDIRECT_AUTHORITY");
            }
            return target;
        } catch (URISyntaxException exception) {
            throw new AgentToolSecurityException("WEB_REDIRECT_INVALID");
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 10 || first == 127 || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)
                    || isIpv4MappedPrivate(bytes);
        }
        return true;
    }

    private static boolean isIpv4MappedPrivate(byte[] bytes) {
        if (bytes.length != 16 || bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 0 || bytes[3] != 0
                || bytes[4] != 0 || bytes[5] != 0 || bytes[6] != 0 || bytes[7] != 0
                || bytes[8] != 0 || bytes[9] != 0 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        return isBlockedAddress(toIpv4(bytes[12], bytes[13], bytes[14], bytes[15]));
    }

    private static InetAddress toIpv4(byte a, byte b, byte c, byte d) {
        try {
            return InetAddress.getByAddress(new byte[]{a, b, c, d});
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new AgentToolSecurityException("WEB_MIME_NOT_ALLOWED");
        }
        String value = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new AgentToolSecurityException("WEB_MIME_NOT_ALLOWED");
        }
        return value;
    }

    private static String normalizeScheme(String value) {
        if (value == null || (!value.equalsIgnoreCase("http") && !value.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("scheme must be http or https");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank() || value.length() > 253 || value.indexOf('.') < 0
                || value.chars().anyMatch(Character::isWhitespace) || value.contains("@")) {
            throw new IllegalArgumentException("host is invalid");
        }
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.endsWith(".") || ascii.contains("..")) {
                throw new IllegalArgumentException("host is invalid");
            }
            return ascii;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("host is invalid", exception);
        }
    }

    private static String canonicalPath(String value) {
        String path = value.startsWith("/") ? value : "/" + value;
        try {
            URI normalized = new URI(null, null, path, null).normalize();
            String result = normalized.getPath();
            if (result == null || !result.startsWith("/") || result.contains("/../") || result.equals("/..")) {
                throw new IllegalArgumentException("path escapes root");
            }
            return result;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("path is invalid", exception);
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() > 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
    }

    private static boolean pathAllowed(String prefix, String path) {
        return path.equals(prefix) || (path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private void auditDenied(ToolExecutionContext context, String errorCode, Map<String, String> safe) {
        if (context != null) {
            audit.record(ToolAuditProjection.denied(AgentToolName.WEB_FETCH, context, errorCode, Instant.now(), safe));
        }
    }
}
