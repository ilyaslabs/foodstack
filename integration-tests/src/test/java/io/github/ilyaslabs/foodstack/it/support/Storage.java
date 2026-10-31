package io.github.ilyaslabs.foodstack.it.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Uploads to object storage with a presigned URL, byte for byte as a browser does. */
public final class Storage {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Storage() {
    }

    /** PUTs {@code body} to the presigned {@code url} with the ticket's headers; returns the status code. */
    public static int put(String url, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("host")) {
                request.header(name, value);
            }
        });
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
