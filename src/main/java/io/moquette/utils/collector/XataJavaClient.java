package io.moquette.utils.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class XataJavaClient {

  private static final Logger logger = LoggerFactory.getLogger(XataJavaClient.class);

  public static void main(String[] args) throws URISyntaxException, IOException, InterruptedException {
    final String payload = "{\n" +
      "  \"IP\": \"192.168.0.1\",\n" +
      "  \"OS\": \"Windows\",\n" +
      "  \"startup_date\": \"2022-12-08T10:19:00+01:00\",\n" +
      "  \"version\": \"0.16\"\n" +
      "}";

    HttpRequest request = HttpRequest.newBuilder()
      .uri(new URI("https://andrea-selva-s-workspace-h24c65.eu-west-1.xata.sh/db/moquette_instances:main/tables/runs/data"))
      .header("Authorization", "Bearer xau_2OfwGoGjqWgOKjP2vOuaSmsshUvFKV1q7")
      .header("Content-Type", "application/json")
      //.header("Host", "Andrea-Selva-s-workspace-h24c65.eu-west-1.xata.sh")
      .POST(HttpRequest.BodyPublishers.ofString(payload))
      .build();

    HttpResponse<String> response = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .build()
      .send(request, HttpResponse.BodyHandlers.ofString());

    logger.info("Response received: {}", response);
  }
}
