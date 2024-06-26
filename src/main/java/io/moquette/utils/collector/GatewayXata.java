package io.moquette.utils.collector;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static io.vertx.core.http.HttpHeaders.CONTENT_LENGTH;

public class GatewayXata extends AbstractVerticle {

  private final Logger logger = LoggerFactory.getLogger(GatewayXata.class);

  private WebClient webClient;
  private String baseUri;
  private String token;
  private DateTimeFormatter dateTimeFormatter;
  private String host;

  @Override
  public void start() {
    logger.info("Start");
    token = config().getString("api_key");
    final String workspace = config().getString("workspace_slug");
    final String region = config().getString("region");
    host = String.format("%s.%s.xata.sh", workspace, region);
    baseUri = "https://" + host;

    dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    final WebClientOptions webClientOptions = new WebClientOptions()
      .setFollowRedirects(true)
      .setUserAgent("Vertx test");

    HttpClient client = vertx.createHttpClient();
    client.redirectHandler(GatewayXata::reconfigureRedirect);
    webClient = WebClient.wrap(client, webClientOptions);

    final Router router = Router.router(vertx);
    router.post().handler(BodyHandler.create());

    final String prefix = "/api/v1";
    router.post(prefix + "/notify").handler(this::collect);

    vertx.createHttpServer()
      .requestHandler(router)
      .listen(8080);
  }

  private void collect(RoutingContext ctx) {
    final HttpServerRequest request = ctx.request();
    final String remoteIpAddr = request.remoteAddress().hostAddress();

    final String localFormattedTime = LocalDateTime.now().format(dateTimeFormatter);
    logger.info("Formatted time: {}", localFormattedTime);

    final JsonObject requestJson = ctx.getBodyAsJson();
    vertx.eventBus()
      .request(GeoIPVerticle.RESOLVER_BUS_ADDRESS, requestJson.getString("remote_ip"))
      .map((Message<Object> msg) -> {

        JsonObject payload = new JsonObject()
          .put("IP", remoteIpAddr)
          .put("startup_date", localFormattedTime);
        if (msg.body() instanceof JsonObject) {
          logger.debug("Location data: {}", msg.body());
          final JsonObject location = (JsonObject) msg.body();
          location.forEach(entry -> payload.put(entry.getKey(), entry.getValue()));
        }

        copyIfPresent("os", payload, requestJson);
        copyIfPresent("cpu_arch", payload, requestJson);
        copyIfPresent("jvm_version", payload, requestJson);
        copyIfPresent("jvm_vendor", payload, requestJson);
        copyIfPresent("remote_ip", payload, requestJson);
        if (requestJson.containsKey("broker_version")) {
          payload.put("version", requestJson.getString("broker_version"));
        }
        copyIfPresent("uuid", payload, requestJson);

        if (requestJson.containsKey("standalone")) {
          payload.put("standalone", requestJson.getBoolean("standalone"));
        }
        if (requestJson.containsKey("max_heap") && !requestJson.getString("max_heap").equals("undefined")) {
          payload.put("max_heap", Long.parseLong(requestJson.getString("max_heap")));
        }
        return payload;
      })
        .flatMap(payload -> {
          return vertx.executeBlocking((Promise<HttpResponse<String>> promise) -> {
            try {
              final HttpResponse<String> response = jdkHttpClientRequest(payload.encode(), host);
              promise.complete(response);
            } catch (URISyntaxException | IOException | InterruptedException ex) {
              promise.fail(ex);
            }
          });
//          return webClient
//            .post(baseUri, "/db/moquette_instances:main/tables/runs/data")
//            .bearerTokenAuthentication(token)
//            .putHeader("Content-Type", "application/json")
//            .putHeader("Host", host)
//            .sendJson(payload);
        })
      .onSuccess(resp -> {
        if (resp.statusCode() != 201) {
//          logger.warn("Problem reaching Xata, status code: {} message: {}, resp: {}", resp.statusCode(), resp.statusMessage(), resp.followedRedirects());
          logger.warn("Problem reaching Xata, status code: {} message: {}", resp.statusCode(), resp);
          ctx.response().setStatusCode(404).end();
        } else {
//          logger.info("Body response: {}", resp.bodyAsJsonObject());
          logger.info("Body response: {}", resp.body());
          ctx.response().setStatusCode(200).end();
        }
      })
      .onFailure(th -> {
        logger.error("Problem accessing Xata", th);
        ctx.fail(502);
      });
  }

  private HttpResponse<String> jdkHttpClientRequest(String jsonPayload, String host) throws URISyntaxException, IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
      .uri(new URI(baseUri + "/db/moquette_instances:main/tables/runs/data"))
      .header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
      .build();

    logger.info("Xata HTTP request: {}", request);

    return java.net.http.HttpClient.newBuilder()
      .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
      .build()
      .send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void copyIfPresent(String fieldName, JsonObject payload, JsonObject requestJson) {
    if (requestJson.containsKey(fieldName)) {
      payload.put(fieldName, requestJson.getString(fieldName));
    }
  }

  private static Future<RequestOptions> reconfigureRedirect(HttpClientResponse resp) {
    // copied from Vert core DEFAULT_HANDLER just removing the skip of handing in case of not be GET or HEAD
    // https://github.com/eclipse-vertx/vert.x/blob/4.2.1/src/main/java/io/vertx/core/http/impl/HttpClientImpl.java#L74-L76
    try {
      int statusCode = resp.statusCode();
      String location = resp.getHeader(HttpHeaders.LOCATION);
      if (location != null && (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308)) {
        HttpMethod m = resp.request().getMethod();
        if (statusCode == 303) {
          m = HttpMethod.GET;
        }
        URI uri = HttpUtils.resolveURIReference(resp.request().absoluteURI(), location);
        boolean ssl;
        int port = uri.getPort();
        String protocol = uri.getScheme();
        char chend = protocol.charAt(protocol.length() - 1);
        if (chend == 'p') {
          ssl = false;
          if (port == -1) {
            port = 80;
          }
        } else if (chend == 's') {
          ssl = true;
          if (port == -1) {
            port = 443;
          }
        } else {
          return null;
        }
        String requestURI = uri.getPath();
        if (requestURI == null || requestURI.isEmpty()) {
          requestURI = "/";
        }
        String query = uri.getQuery();
        if (query != null) {
          requestURI += "?" + query;
        }
        RequestOptions options = new RequestOptions();
        options.setMethod(m);
        options.setHost(uri.getHost());
        options.setPort(port);
        options.setSsl(ssl);
        options.setURI(requestURI);
        options.setHeaders(resp.request().headers());
        options.removeHeader(CONTENT_LENGTH);
        return Future.succeededFuture(options);
      }
      return null;
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }
}
