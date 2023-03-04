package io.moquette.utils.collector;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GeoIPVerticle extends AbstractVerticle {

  public static final String RESOLVER_BUS_ADDRESS = "ipresolver.lookup";

  private final Logger logger = LoggerFactory.getLogger(GeoIPVerticle.class);

  private static final String GEOLITE_DOWNLOAD_TEMPLATE_URI = "/app/geoip_download?edition_id=GeoLite2-City&license_key=%s&suffix=tar.gz";

  private DatabaseReader reader;

  @Override
  public void start(Promise<Void> promise) {
    logger.info("GeoIP starting");
    final FileSystem filesystem = vertx.fileSystem();
    logger.info("Local geolite DB doesn't exist, dowloading...");
    filesystem.mkdir("geolite")
      .onFailure(event -> {
        // directory already exists, so just create the GeoIP DB
        try {
          logger.info("GeoIP DB already present, instantiate the client");
          createGeoIPDB();
        } catch (IOException e) {
          promise.fail(e);
        }
      })
      .onSuccess(_void -> {
        filesystem.open("geolite/geolite.tar.gz", new OpenOptions().setCreateNew(true).setWrite(true))
          .flatMap(this::downloadFile)
          .flatMap(response -> {
            logger.info("Response completed");
            return vertx.executeBlocking(GeoIPVerticle.this::extractGeoTar);
          })
          .onSuccess(promise::complete)
          .onFailure((Throwable err) -> {
            logger.error("Error downloading file", err);
            promise.fail(err);
          });
      });

    // subscribe to the topic for query reply
    vertx.eventBus().<String>consumer(RESOLVER_BUS_ADDRESS, msg -> {
      final String ipAddress = msg.body();
      vertx.executeBlocking(new Handler<Promise<JsonObject>>() {
        @Override
        public void handle(Promise<JsonObject> promise) {
          try {
            final JsonObject resolved = resolveIP(ipAddress);
            promise.complete(resolved);
          } catch (IOException | GeoIp2Exception e) {
            e.printStackTrace();
            promise.fail(e);
          }
        }
      }).onSuccess(msg::reply);
    });
  }

  private Future<HttpResponse<Void>> downloadFile(AsyncFile destination) {
    final WebClient webClient = WebClient.create(vertx);
    final String licenseKey = config().getString("license_key");
    final String downloadUri = String.format(GEOLITE_DOWNLOAD_TEMPLATE_URI, licenseKey);
    return webClient.get("download.maxmind.com", downloadUri)
      .expect(ResponsePredicate.SC_SUCCESS)
      .as(BodyCodec.pipe(destination))
      .send();
  }

  // blocking code
  private void extractGeoTar(Promise<Void> promise) {
    logger.info("Blocking code unpacking");
    File targz = new File("geolite/geolite.tar.gz");
    if (!targz.exists()) {
      logger.error("Tar gz file {} doesn't exists", targz);
      promise.fail("File doesn't exists");
    }

    final Path destinaton = Path.of("./geolite");
    try (FileInputStream fin = new FileInputStream(targz)) {
      extractTarGZ(fin, destinaton);
      createGeoIPDB();
    } catch (FileNotFoundException e) {
      promise.fail("File doesn't exists");
    } catch (IOException e) {
      promise.fail(e);
    }
  }

  private void createGeoIPDB() throws IOException {
    File database = new File("geolite/GeoLite2-City.mmdb");
    reader = new DatabaseReader.Builder(database).build();
    logger.info("Successfully started GeoIP verticle");
  }

  // blocking method that operates with Java API
  private void extractTarGZ(InputStream in, Path destination) throws IOException {
    GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(in);
    try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
      TarArchiveEntry entry;

      while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
        // If the entry is a directory, create the directory.
        if (entry.isDirectory()) {
          File f = new File(entry.getName());
          boolean created = f.mkdir();
          if (!created) {
            logger.debug("Unable to create directory '{}', during extraction of archive contents.",
              f.getAbsolutePath());
          }
        } else {
          int count;
          final int bufferSize = 8 * 1024;
          byte[] data = new byte[bufferSize];
          FileOutputStream fos = new FileOutputStream(flattenTo(destination, entry).toString(), false);
          try (BufferedOutputStream dest = new BufferedOutputStream(fos, bufferSize)) {
            while ((count = tarIn.read(data, 0, bufferSize)) != -1) {
              dest.write(data, 0, count);
            }
          }
        }
      }

      logger.info("Untar completed successfully!");
    }
  }

  private Path flattenTo(Path destination, TarArchiveEntry entry) {
    return destination.resolve(Paths.get(entry.getName()).getFileName());
  }

  private JsonObject resolveIP(String ip) throws IOException, GeoIp2Exception {
    InetAddress ipAddress = InetAddress.getByName(ip);
    try {
      CityResponse response = reader.city(ipAddress);
      return new JsonObject()
        .put("nation", response.getCountry().getName())
        .put("region", response.getMostSpecificSubdivision().getName())
        .put("city", response.getCity().getName())
        .put("latitude", response.getLocation().getLatitude())
        .put("longitude", response.getLocation().getLongitude());
    } catch (AddressNotFoundException ex) {
      return new JsonObject();
    }
  }
}
