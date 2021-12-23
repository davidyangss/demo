# Instruction
* Java11
* I reproduced the problem on MacOS. Did not continue to experiment on Linux. I think the result should be consistent
* Use Apache-Benchmark for stress testing
* SimpleController processes Http requests, and uses WebClient to request back-end services, and returns back-end services' responses.
* WebClientConfiguration configures WebClient. 
* I added reactor-related source code to the project, but I only added logs to it. The purpose can better show the problem.

# Begin
* Use nginx to simulate back-end services. the default port is 80.
  SimpleController.utcUri default = http://localhost/echo/utc
```properties
backend.utc.now.uri=http://localhost/echo/utc
```
``` 
# Nginx config
location /echo/utc {
    return 200 "NOW: $date_gmt";
}
```
* Run ab.sh, it is in the project directory.
```shell
cat logs/yss-demo.log | grep "Created a new pooled channel" | awk '{print $6}' | sort | uniq -c
  93 [reactor-http-nio-11]
   3 [reactor-http-nio-12]
   1 [reactor-http-nio-3]
   1 [reactor-http-nio-8]

cat logs/yss-demo.log | grep ExchangeFunctions | grep "HTTP " | awk '{print $6}' | sort | uniq -c
  63 [reactor-http-nio-10]
  62 [reactor-http-nio-11]
  62 [reactor-http-nio-12]
  62 [reactor-http-nio-13]
  62 [reactor-http-nio-14]
  62 [reactor-http-nio-15]
  62 [reactor-http-nio-16]
  62 [reactor-http-nio-1]
  62 [reactor-http-nio-2]
  63 [reactor-http-nio-3]
  63 [reactor-http-nio-4]
  63 [reactor-http-nio-5]
  63 [reactor-http-nio-6]
  63 [reactor-http-nio-7]
  63 [reactor-http-nio-8]
  63 [reactor-http-nio-9]

cat logs/yss-demo.log | grep -E "SimpleDequePool|ColocatedEventLoopGroup" | less
```
* WebClientConfiguration
```java
    @Bean
    @Lazy
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    public WebClientCustomizer loadbalancedWebClientCustomizer(@Value("${debug:false}") boolean debug) {
        ExchangeFilterFunction filter = (request, next) -> next.exchange(request)
        .subscribeOn(Schedulers.boundedElastic()) // After adding subscribeOn, there is no hot thread anymore
        .doFinally(signalType -> log.debug(
        "{} exchange filter function, signalType = {}", request.logPrefix(), signalType));

        return (builder) -> builder.filters((filters) -> filters.add(filter));
    }
```
```shell
cat logs/yss-demo.log | grep "Created a new pooled channel" | awk '{print $6}' | sort | uniq -c
   4 [reactor-http-nio-10]
   5 [reactor-http-nio-11]
   5 [reactor-http-nio-12]
   5 [reactor-http-nio-13]
   4 [reactor-http-nio-14]
   4 [reactor-http-nio-15]
   3 [reactor-http-nio-16]
   3 [reactor-http-nio-1]
   5 [reactor-http-nio-2]
   4 [reactor-http-nio-3]
   6 [reactor-http-nio-4]
   6 [reactor-http-nio-5]
   5 [reactor-http-nio-6]
   6 [reactor-http-nio-7]
   5 [reactor-http-nio-8]
   4 [reactor-http-nio-9]

```
