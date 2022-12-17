package io.moquette.utils.collector;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class Main {
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();

    JsonObject config = new JsonObject()
      .put("api_key", System.getenv().get("API_KEY"))
      .put("workspace_slug", System.getenv().get("WORKSPACE"))
      .put("region", System.getenv().get("REGION"));
    DeploymentOptions options = new DeploymentOptions().setConfig(config);
    vertx.deployVerticle(new GatewayXata(), options);
  }
}
