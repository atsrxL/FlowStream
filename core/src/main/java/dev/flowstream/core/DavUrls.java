package dev.flowstream.core;

import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.Locale;

/** URL canonicalization and credential scope. Never accepts embedded credentials. */
public final class DavUrls {
    private final URI root;
    public DavUrls(String url, boolean allowHttp) {
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && allowHttp))
                throw new IllegalArgumentException("请用 HTTPS；HTTP 必须单独确认允许");
            if (u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
                throw new IllegalArgumentException("WebDAV 地址不能含账号、查询参数或片段");
            String raw = u.getRawPath() == null ? "/" : u.getRawPath();
            if (!raw.endsWith("/")) raw += "/";
            MediaPaths.normalize(decode(raw));
            root = URI.create(scheme + "://" + u.getRawAuthority() + raw).normalize();
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("WebDAV 地址格式错误", e); }
    }
    public String root() { return root.toASCIIString(); }
    public URI uri(String path, boolean directory) {
        String p = MediaPaths.normalize(path);
        StringBuilder b = new StringBuilder(root());
        if (!p.isEmpty()) {
            String[] parts = p.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (i != 0) b.append('/');
                b.append(encode(parts[i]));
            }
        }
        if (directory && b.charAt(b.length() - 1) != '/') b.append('/');
        return URI.create(b.toString());
    }
    public boolean allows(URI target) {
        if (!sameOrigin(root, target) || target.getUserInfo() != null || target.getFragment() != null) return false;
        try {
            String path = decode(target.getRawPath());
            MediaPaths.normalize(path); // reject dot traversal even when percent encoded
            String rp = decode(root.getRawPath());
            return path.equals(rp.substring(0, rp.length() - 1)) || path.startsWith(rp);
        } catch (IllegalArgumentException e) { return false; }
    }
    public String relative(URI currentDirectory, String href) {
        URI u;
        try { u = currentDirectory.resolve(href.trim().replace(" ", "%20")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("非法 WebDAV href", e); }
        if (u.getQuery() != null || !allows(u)) throw new IllegalArgumentException("拒绝越界 WebDAV 路径");
        String p = decode(u.getRawPath()), rp = decode(root.getRawPath());
        if (p.equals(rp.substring(0, rp.length() - 1))) return "";
        return MediaPaths.normalize(p.substring(rp.length()));
    }
    private static String decode(String raw) {
        if (raw == null) return "/";
        // Encoded separators are ambiguous across proxies and servers: fail closed.
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || raw.indexOf('\\') >= 0)
            throw new IllegalArgumentException("不接受编码后的路径分隔符");
        try { return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    public static boolean sameOrigin(URI a, URI b) {
        return b.getHost() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }
    private static int port(URI u) { return u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80); }
}
