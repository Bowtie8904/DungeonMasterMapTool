package dmmt.online;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** HTTP client that behaves like a current browser: it declares the Online Play contract unless a test sets its own. */
final class ContractHttpClient extends HttpClient {
    private final HttpClient delegate = HttpClient.newHttpClient();

    private static HttpRequest declare(HttpRequest request) {
        if (request.headers().firstValue(OnlineProtocol.CONTRACT_HEADER).isPresent()) return request;
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .header(OnlineProtocol.CONTRACT_HEADER, Integer.toString(OnlineProtocol.AUTOMATION_COMPATIBILITY_VERSION)).build();
    }

    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return delegate.send(declare(request), handler);
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(declare(request), handler);
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        return delegate.sendAsync(declare(request), handler, push);
    }
    @Override public WebSocket.Builder newWebSocketBuilder() {
        WebSocket.Builder inner = delegate.newWebSocketBuilder();
        return new WebSocket.Builder() {
            @Override public WebSocket.Builder header(String name, String value) { inner.header(name, value); return this; }
            @Override public WebSocket.Builder connectTimeout(Duration timeout) { inner.connectTimeout(timeout); return this; }
            @Override public WebSocket.Builder subprotocols(String most, String... lesser) { inner.subprotocols(most, lesser); return this; }
            @Override public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                String text = uri.toString();
                if (!text.contains(OnlineProtocol.CONTRACT_PARAM + "="))
                    text += (text.contains("?") ? "&" : "?") + OnlineProtocol.CONTRACT_PARAM + "=" + OnlineProtocol.AUTOMATION_COMPATIBILITY_VERSION;
                return inner.buildAsync(URI.create(text), listener);
            }
        };
    }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
}
