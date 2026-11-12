package com.exenova.link.image;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared, size-limited HTTP image download used by the map features. */
public final class ImageDownloader {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private ImageDownloader() { }

    public static byte[] download(String url, long maxBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ExeNovaLink").GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new Exception("HTTP " + response.statusCode());
        byte[] body = response.body();
        if (body.length > Math.max(1, maxBytes)) throw new Exception("image too large (" + body.length + " bytes)");
        return body;
    }
}
