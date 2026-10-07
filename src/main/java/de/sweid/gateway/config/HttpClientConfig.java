package de.sweid.gateway.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One shared JDK HttpClient (HTTP/2, connection reuse) behind every provider RestClient. Blocking
 * calls are fine here: the app runs on virtual threads. The builder keeps Boot's message
 * converters so JSON is written in snake_case like the rest of the API.
 */
@Configuration
public class HttpClientConfig {

  @Bean
  public HttpClient jdkHttpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Bean
  public RestClient.Builder restClientBuilder(
      RestClientBuilderConfigurer configurer, HttpClient httpClient) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(120));
    return configurer.configure(RestClient.builder()).requestFactory(factory);
  }
}
