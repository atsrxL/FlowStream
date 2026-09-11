package dev.flowstream.player.net;

import dev.flowstream.core.DavUrls;
import dev.flowstream.player.data.Server;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class HttpNetwork {
    private HttpNetwork() { }
    public static OkHttpClient client(Server s) {
        DavUrls scope = new DavUrls(s.url, s.allowHttp);
        String credentials = s.username.isEmpty() ? null : Credentials.basic(s.username, s.password, StandardCharsets.UTF_8);
        return new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(false)
                .addNetworkInterceptor(chain -> {
                    Request input = chain.request();
                    if (!scope.allows(URI.create(input.url().toString()))) throw new IOException("拒绝跨服务器或超出根目录的重定向 / 媒体请求");
                    Request.Builder b = input.newBuilder().header("Accept-Encoding", "identity");
                    if (credentials != null) b.header("Authorization", credentials);
                    else b.removeHeader("Authorization");
                    Response result = chain.proceed(b.build());
                    String range = input.header("Range");
                    if (range != null && range.matches("bytes=[1-9][0-9]*-.*") && result.code() == 200) {
                        result.close(); throw new IOException("服务器不支持 Range，无法可靠定位视频；请启用字节范围请求");
                    }
                    return result;
                }).build();
    }
    public static void close(OkHttpClient client) {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
