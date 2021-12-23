package yss.demo;

import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.reactive.function.client.ReactorNettyHttpClientMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ReactorNetty;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ProxyProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static reactor.netty.http.client.HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER;

@Setter
@Slf4j

@Configuration(proxyBeanMethods = true)
@ConfigurationProperties(prefix = "webclient", ignoreUnknownFields = true)
public class WebClientConfiguration {
    private Duration connectTimout = Duration.ofSeconds(3); //默认3000
    private Duration readTimout = Duration.ofSeconds(3); //默认3000
    private Duration writeTimout = Duration.ZERO;
    private Duration sslHandshakeTimeout = Duration.ZERO;

    private boolean keeplive = true;
    private boolean compress = true;
    private boolean followRedirect = true;

    @Primary
    @Bean
    public ReactorResourceFactory reactorClientResourceFactory() {
        ReactorResourceFactory resourceFactory = new ReactorResourceFactory();
        resourceFactory.setUseGlobalResources(false);

        int maxConnections = Integer.parseInt(System.getProperty(ReactorNetty.POOL_MAX_CONNECTIONS, "500")); // 500 默认
        int pendingAcquireMaxCount = Integer.parseInt(System.getProperty("reactor.netty.pool.acquireMaxCount", "-1")); // -1 默认
        ConnectionProvider provider = ConnectionProvider.builder("RX")
                .maxConnections(maxConnections)  //-Dreactor.netty.pool.maxConnections
                .pendingAcquireMaxCount(pendingAcquireMaxCount) //如果 maxConnections少，pendingAcquireMaxCount要大一点
                //                .maxIdleTime(relayServerProps.getMaxIdleTime())  //-Dreactor.netty.pool.maxIdleTime
                //                .maxLifeTime(relayServerProps.getMaxLifeTime())  //-Dreactor.netty.pool.maxLifeTime
                .metrics(true)
                //                .pendingAcquireTimeout(relayServerProps.getPendingAcquireTimeout()) //-Dreactor.netty.pool.acquireTimeout
                .build();
        HttpResources httpResources = HttpResources.set(provider);
        resourceFactory.setConnectionProviderSupplier(() -> httpResources);
        resourceFactory.setLoopResourcesSupplier(() -> httpResources);

        log.info("ReactorResourceFactory({}) max connections: {}, acquireMaxCount: {}, HttpResources = {} [from ConnectionProvider = {}]",
                resourceFactory, provider.maxConnections(), pendingAcquireMaxCount, httpResources, provider);
        return resourceFactory;
    }

    @Bean
    @Scope("prototype")
    public WebClient.Builder webClientBuilder(
            ObjectProvider<WebClientCustomizer> customizerProvider,
            ReactorClientHttpConnector reactorClientHttpConnector,
            @Value("${debug:false}") boolean debug) {
        WebClient.Builder builder = WebClient.builder();
        customizerProvider.orderedStream().forEach((customizer) -> {
            customizer.customize(builder);
        });
        log.info("ReactorClientHttpConnector = {}, WebClient.Builder = {}", reactorClientHttpConnector, builder);

        if (debug || log.isDebugEnabled()) {
            builder.filters(exchangeFilterFunctions ->
                    exchangeFilterFunctions.forEach(exchangeFilterFunction ->
                            log.debug("WebClientBuilder: {}, exchangeFilterFunction: {}", builder,
                                    exchangeFilterFunction.getClass())));
        }
        return builder;
    }

    @Bean
    public ReactorNettyHttpClientMapper reactorNettyHttpClientMapper(
            ObjectProvider<ReactorNettyHttpClientMapper> mapperProvider,
            @Value("${debug:false}") boolean debug,
            @Autowired(required = false) ProxyGetter proxyGetter) {
        Function<HttpClient, HttpClient> compress =
                httpClient -> httpClient.compress(this.compress);

        Function<HttpClient, HttpClient> keepAlive =
                httpClient -> httpClient.keepAlive(this.keeplive);

        Function<HttpClient, HttpClient> followRedirect =
                httpClient -> httpClient.followRedirect(this.followRedirect);

        Function<HttpClient, HttpClient> connectTimout =
                httpClient -> httpClient.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) this.connectTimout.toMillis());

        Function<HttpClient, HttpClient> writeTimout =
                httpClient -> this.writeTimout.isZero() ?
                        httpClient
                        :
                        httpClient.doOnConnected(conn -> conn.addHandlerLast(
                                new WriteTimeoutHandler((int) this.writeTimout.toMillis(),
                                        TimeUnit.MILLISECONDS)));

        Function<HttpClient, HttpClient> readTimout = httpClient -> httpClient.doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler((int) this.readTimout.toMillis(),
                        TimeUnit.MILLISECONDS)));

        Logger httpClientLogger = LoggerFactory.getLogger(HttpClient.class);
        Function<HttpClient, HttpClient> debuglog = (debug || httpClientLogger.isDebugEnabled()) ?
                httpClient -> httpClient.wiretap(HttpClient.class.getName(), LogLevel.DEBUG, AdvancedByteBufFormat.SIMPLE) :
                client -> client;

        Function<HttpClient, HttpClient> globalProxy = httpClient -> proxyGetter == null ?
                httpClient :
                httpClient.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
                    URI proxy = proxyGetter.apply(remoteAddress);
                    log.info("Use proxy: {}. if null, igrone", proxy);
                    if (proxy == null) {
                        return;
                    }

                    if ("socks5".equalsIgnoreCase(proxy.getScheme())) {
                        channel.pipeline().addFirst(new Socks5ProxyHandler(
                                new InetSocketAddress(proxy.getHost(),
                                        proxy.getPort()))); // 可设置username
                        return;
                    }

                    if ("socks4".equalsIgnoreCase(proxy.getScheme())) {
                        channel.pipeline().addFirst(new Socks4ProxyHandler(
                                new InetSocketAddress(proxy.getHost(),
                                        proxy.getPort()))); // 可设置username
                        return;
                    }

                    channel.pipeline().addFirst(new HttpProxyHandler(
                            new InetSocketAddress(proxy.getHost(), proxy.getPort())));
                });

        Function<HttpClient, HttpClient> secure =
                httpClient -> sslHandshakeTimeout.isZero() ? httpClient : httpClient.secure(
                        SslProvider.addHandlerConfigurator(SslProvider
                                        .builder()
                                        .sslContext(Http11SslContextSpec.forClient())
                                        .handshakeTimeout(
                                                sslHandshakeTimeout)
                                        .build(),
                                HOSTNAME_VERIFICATION_CONFIGURER));

        Function<HttpClient, HttpClient> protocol =
                httpClient -> httpClient.protocol(HttpProtocol.HTTP11);

        return httpClient -> protocol.andThen(compress)
                .andThen(keepAlive)
                .andThen(followRedirect)
                .andThen(connectTimout)
                .andThen(readTimout)
                .andThen(writeTimout)
                .andThen(debuglog)
                .andThen(secure)
                .andThen(globalProxy)
                .apply(httpClient);
    }

    @Bean
    @Lazy
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    public WebClientCustomizer loadbalancedWebClientCustomizer(@Value("${debug:false}") boolean debug) {
        ExchangeFilterFunction filter = (request, next) -> next.exchange(request)
//                .subscribeOn(Schedulers.boundedElastic()) // After adding subscribeOn, there is no hot thread anymore
                .doFinally(signalType -> log.debug(
                        "{} exchange filter function, signalType = {}", request.logPrefix(), signalType));

        return (builder) -> builder.filters((filters) -> filters.add(filter));
    }

    /**
     * 需要新创建 ReactorClientHttpConnector(getReactorResourceFactory(), Function<HttpClient, HttpClient>);
     *
     * @return
     */
    public Function<URI, Function<HttpClient, HttpClient>> proxy() {
        return proxy -> httpClient -> httpClient.proxy((typeSpec) -> typeSpec.type(
                        ProxyProvider.Proxy.valueOf(proxy.getScheme().toUpperCase()))
                .host(proxy.getHost()).port(proxy.getPort()));
    }

    public interface ProxyGetter extends Function<SocketAddress, URI> {

    }
}
