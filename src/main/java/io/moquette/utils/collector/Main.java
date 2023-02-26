package io.moquette.utils.collector;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;

public class Main {
  public static void main(String[] args) {
//    final VertxOptions vertxOptions = new VertxOptions();
//    vertxOptions.getAddressResolverOptions().setOptResourceEnabled(false);
//    Vertx vertx = Vertx.vertx(vertxOptions);
    Vertx vertx = Vertx.vertx();

    JsonObject geoipConfig = new JsonObject()
      .put("license_key", System.getenv().get("GEOLITE2_LICENSE_KEY"));
    vertx
      .deployVerticle(new GeoIPVerticle(), new DeploymentOptions().setConfig(geoipConfig))
      .onFailure(th -> {
        System.out.println("Can't start GeoIP lookup verticle");
        System.exit(1);
      });

    JsonObject config = new JsonObject()
      .put("api_key", System.getenv().get("API_KEY"))
      .put("workspace_slug", System.getenv().get("WORKSPACE"))
      .put("region", System.getenv().get("REGION"));
    DeploymentOptions options = new DeploymentOptions().setConfig(config);
    vertx.deployVerticle(new GatewayXata(), options);
  }
}
