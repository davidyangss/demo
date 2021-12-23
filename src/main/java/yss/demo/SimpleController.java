package yss.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class SimpleController {

    @Autowired
    WebClient.Builder webClientBuilder;

    @Value("${backend.utc.now.uri:http://localhost/echo/utc}")
    String utcUri;


    @GetMapping("/UTC/now")
    public Mono<String> echo() {
        return webClientBuilder
                .build()
                .get()
                .uri(utcUri)
                .retrieve()
                .bodyToMono(String.class);
    }
}
