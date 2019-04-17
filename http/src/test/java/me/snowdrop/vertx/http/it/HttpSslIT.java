package me.snowdrop.vertx.http.it;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.net.ssl.SSLHandshakeException;

import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import me.snowdrop.vertx.http.server.VertxServerHttpRequest;
import me.snowdrop.vertx.http.server.properties.HttpServerOptionsCustomizer;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.junit.Assume.assumeTrue;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.noContent;
import static org.springframework.web.reactive.function.server.ServerResponse.status;

public class HttpSslIT extends TestBase {

    private static final JksOptions SERVER_KEYSTORE = new JksOptions()
        .setPath("target/test-classes/tls/server-keystore.jks")
        .setPassword("wibble");

    private static final JksOptions SERVER_TRUSTSTORE = new JksOptions()
        .setPath("target/test-classes/tls/server-truststore.jks")
        .setPassword("wibble");

    private static final JksOptions CLIENT_KEYSTORE = new JksOptions()
        .setPath("target/test-classes/tls/client-keystore.jks")
        .setPassword("wibble");

    private static final JksOptions CLIENT_TRUSTSTORE = new JksOptions()
        .setPath("target/test-classes/tls/client-truststore.jks")
        .setPassword("wibble");

    private static final String KEY_PATH = "target/test-classes/tls/server-key.pem";

    private static final String CERT_PATH = "target/test-classes/tls/server-cert.pem";

    @After
    public void tearDown() {
        stopServer();
    }

    @Test
    public void testSecureRequest() {
        testSecureRequest(false);
    }

    @Test
    public void testSecureRequestWithAlpn() {
        assumeTrue("Neither OpenSSL nor Java 9 or higher is in a classpath", isOpenSsl() || isJava9());
        testSecureRequest(true);
    }

    @Test
    public void testUntrustedClient() {
        testUntrustedClient(false);
    }

    @Test
    public void testUntrustedClientWithAlpn() {
        assumeTrue("Neither OpenSSL nor Java 9 or higher is in a classpath", isOpenSsl() || isJava9());
        testUntrustedClient(true);
    }

    @Test
    public void testDefaultEngine() {
        testEngine(false, Engine.NONE);
    }

    @Test
    public void testDefaultEngineWithAlpn() {
        assumeTrue("Neither OpenSSL nor Java 9 or higher is in a classpath", isOpenSsl() || isJava9());
        testEngine(true, Engine.NONE);
    }

    @Test
    public void testOpenSslEngine() {
        assumeTrue("OpenSSL is not in a classpath", isOpenSsl());
        testEngine(false, Engine.OPENSSL);
    }

    @Test
    public void testOpenSslEngineWithAlpn() {
        assumeTrue("OpenSSL is not in a classpath", isOpenSsl());
        testEngine(true, Engine.OPENSSL);
    }

    @Test
    public void testJdkEngine() {
        testEngine(false, Engine.JDK);
    }

    @Test
    public void testJdkEngineWithAlpn() {
        assumeTrue("Java 9 or higher is not in a classpath", isJava9());
        testEngine(true, Engine.JDK);
    }

    private void testSecureRequest(boolean useAlpn) {
        Properties serverProperties = new Properties();
        serverProperties.setProperty("vertx.http.server.ssl", "true");
        serverProperties.setProperty("vertx.http.server.useAlpn", Boolean.toString(useAlpn));
        serverProperties.setProperty("vertx.http.server.client-auth", ClientAuth.REQUIRED.name());
        serverProperties.setProperty("server.ssl.key-store-type", "JKS");
        serverProperties.setProperty("server.ssl.key-store", SERVER_KEYSTORE.getPath());
        serverProperties.setProperty("server.ssl.key-store-password", SERVER_KEYSTORE.getPassword());
        serverProperties.setProperty("server.ssl.trust-store-type", "JKS");
        serverProperties.setProperty("server.ssl.trust-store", SERVER_TRUSTSTORE.getPath());
        serverProperties.setProperty("server.ssl.trust-store-password", SERVER_TRUSTSTORE.getPassword());

        startServer(serverProperties, useAlpn ? NoopHttp11Router.class : NoopHttp2Router.class);

        HttpClientOptions clientOptions = new HttpClientOptions()
            .setSsl(true)
            .setUseAlpn(useAlpn)
            .setProtocolVersion(useAlpn ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1)
            .setKeyStoreOptions(CLIENT_KEYSTORE)
            .setTrustOptions(CLIENT_TRUSTSTORE);

        HttpStatus status = getWebClient(clientOptions)
            .get()
            .exchange()
            .map(ClientResponse::statusCode)
            .block(Duration.ofSeconds(2));

        assertThat(status).isEqualTo(status);
    }

    private void testUntrustedClient(boolean useAlpn) {
        Properties serverProperties = new Properties();
        serverProperties.setProperty("vertx.http.server.ssl", "true");
        serverProperties.setProperty("vertx.http.server.useAlpn", Boolean.toString(useAlpn));
        serverProperties.setProperty("vertx.http.server.client-auth", ClientAuth.REQUIRED.name());
        serverProperties.setProperty("server.ssl.key-store-type", "JKS");
        serverProperties.setProperty("server.ssl.key-store", SERVER_KEYSTORE.getPath());
        serverProperties.setProperty("server.ssl.key-store-password", SERVER_KEYSTORE.getPassword());

        startServer(serverProperties, useAlpn ? NoopHttp2Router.class : NoopHttp11Router.class);

        HttpClientOptions options = new HttpClientOptions()
            .setSsl(true)
            .setUseAlpn(useAlpn)
            .setProtocolVersion(useAlpn ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1)
            .setKeyStoreOptions(CLIENT_KEYSTORE)
            .setTrustOptions(CLIENT_TRUSTSTORE);

        try {
            getWebClient(options)
                .get()
                .exchange()
                .block(Duration.ofSeconds(2));
            fail("SSLHandshakeException expected");
        } catch (RuntimeException e) {
            assertThat(e.getCause()).isInstanceOf(SSLHandshakeException.class);
        }
    }

    private void testEngine(boolean useAlpn, Engine engine) {
        Properties serverProperties = new Properties();
        serverProperties.setProperty("vertx.http.server.ssl", "true");
        serverProperties.setProperty("vertx.http.server.useAlpn", Boolean.toString(useAlpn));

        HttpClientOptions clientOptions = new HttpClientOptions()
            .setSsl(true)
            .setUseAlpn(useAlpn)
            .setTrustAll(true)
            .setProtocolVersion(useAlpn ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1);

        List<Class> classes = new LinkedList<>();
        classes.add(KeyCertCustomizer.class);
        classes.add(useAlpn ? NoopHttp2Router.class : NoopHttp11Router.class);

        switch (engine) {
            case JDK:
                classes.add(JdkSslEngineOptionsCustomizer.class);
                clientOptions.setSslEngineOptions(new JdkSSLEngineOptions());
                break;
            case OPENSSL:
                classes.add(OpenSslEngineOptionsCustomizer.class);
                clientOptions.setSslEngineOptions(new OpenSSLEngineOptions());
        }

        startServer(serverProperties, classes.toArray(new Class[]{}));

        HttpStatus status = getWebClient(clientOptions)
            .get()
            .exchange()
            .map(ClientResponse::statusCode)
            .block(Duration.ofSeconds(2));

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private boolean isJava9() {
        try {
            HttpSslIT.class.getClassLoader().loadClass("java.lang.invoke.VarHandle");
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private boolean isOpenSsl() {
        try {
            HttpSslIT.class.getClassLoader().loadClass("io.netty.internal.tcnative.SSL");
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private enum Engine {
        NONE,
        JDK,
        OPENSSL
    }

    @Configuration
    static class KeyCertCustomizer {
        @Bean
        public HttpServerOptionsCustomizer keyCertCustomizer() {
            return options -> {
                PemKeyCertOptions cert = new PemKeyCertOptions()
                    .setKeyPath(KEY_PATH)
                    .setCertPath(CERT_PATH);

                options.setKeyCertOptions(cert);

                return options;
            };
        }
    }

    @Configuration
    static class OpenSslEngineOptionsCustomizer {
        @Bean
        public HttpServerOptionsCustomizer openSslEngineOptionsCustomizer() {
            return options -> options.setSslEngineOptions(new OpenSSLEngineOptions());
        }
    }

    @Configuration
    static class JdkSslEngineOptionsCustomizer {
        @Bean
        public HttpServerOptionsCustomizer jdkSslEngineOptionsCustomizer() {
            return options -> options.setSslEngineOptions(new JdkSSLEngineOptions());
        }
    }

    @Configuration
    static class NoopHttp2Router {
        @Bean
        public RouterFunction<ServerResponse> noopHttp2Router() {
            return route()
                .GET("/", request -> {
                    HttpServerRequest vertxRequest = getHttpServerRequest(request);

                    System.out.println(vertxRequest.sslSession());
                    if (!HttpVersion.HTTP_2.equals(vertxRequest.version())) {
                        return status(HttpStatus.BAD_REQUEST).syncBody("Not HTTP2 request");
                    }
                    if (!vertxRequest.isSSL()) {
                        return status(HttpStatus.BAD_REQUEST).syncBody("Not SSL request");
                    }
                    return noContent().build();
                })
                .build();
        }

        private HttpServerRequest getHttpServerRequest(ServerRequest request) {
            return ((VertxServerHttpRequest) request.exchange().getRequest()).getNativeRequest();
        }
    }

    @Configuration
    static class NoopHttp11Router {
        @Bean
        public RouterFunction<ServerResponse> noopHttp11Router() {
            return route()
                .GET("/", request -> {
                    HttpServerRequest vertxRequest = getHttpServerRequest(request);

                    if (!HttpVersion.HTTP_1_1.equals(vertxRequest.version())) {
                        return status(HttpStatus.BAD_REQUEST).syncBody("Not HTTP1.1 request");
                    }
                    if (!vertxRequest.isSSL()) {
                        return status(HttpStatus.BAD_REQUEST).syncBody("Not SSL request");
                    }
                    return noContent().build();
                })
                .build();
        }

        private HttpServerRequest getHttpServerRequest(ServerRequest request) {
            return ((VertxServerHttpRequest) request.exchange().getRequest()).getNativeRequest();
        }
    }
}