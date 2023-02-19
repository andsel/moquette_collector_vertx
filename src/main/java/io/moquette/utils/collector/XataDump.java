package io.moquette.utils.collector;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XataDump {

  static class XataClient {

    private HttpURLConnection con;

    XataClient() throws IOException {
      final String apikey = System.getenv().get("API_KEY");
      final String workspace = System.getenv().get("WORKSPACE");
      final String region = System.getenv().get("REGION");

      String host = String.format("%s.%s.xata.sh", workspace, region);
      URL url = new URL("https://" + host + "/db/moquette_instances:main/tables/runs/query");
      con = (HttpURLConnection) url.openConnection();
      con.setRequestMethod("POST");
      con.setRequestProperty("Content-Type", "application/json");
      con.setRequestProperty("Accept", "application/json");
      con.setRequestProperty("Authorization", "Bearer " + apikey);
      con.setRequestProperty("Host", host);
      con.setInstanceFollowRedirects(true);
    }

    String post(String pageRequestBody) throws IOException {
      final byte[] input = pageRequestBody.getBytes("utf-8");
      con.setDoOutput(true);
      try (OutputStream os = con.getOutputStream()) {
        os.write(input, 0, input.length);
        os.flush();
      } catch (IOException e) {
        e.printStackTrace();
      }

      int status = con.getResponseCode();
      System.out.println("Response Code: " + status);
      if (status >= 400) {
        throw new RuntimeException("Problem getting data, response with code " + status);
      }

      boolean redirect = false;

      // normally, 3xx is redirect
      if (status != HttpURLConnection.HTTP_OK) {
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
          || status == HttpURLConnection.HTTP_MOVED_PERM
          || status == HttpURLConnection.HTTP_SEE_OTHER) {
          redirect = true;
        }
      }

      if (redirect) {

        // get redirect url from "location" header field
        String newUrl = con.getHeaderField("Location");

        // open the new connnection again
        con = (HttpURLConnection) new URL(newUrl).openConnection();
        con.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
        con.addRequestProperty("User-Agent", "Mozilla");
        con.addRequestProperty("Referer", "google.com");
        con.setRequestMethod("POST");

        // POST
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
          os.write(input, 0, input.length);
        }

        System.out.println("Redirect to URL: " + newUrl);
      }

      StringBuffer content = reaBody(con);
      return content.toString();
    }

    void close() {
      con.disconnect();
    }
  }

  public static void main(String[] args) throws IOException {
    XataClient client = new XataClient();

    // POST
    String pageRequestBody = "{\n" +
      "  \"page\": {\n" +
      "    \"size\": 200\n" +
      "   }" +
      "}";

    FileWriter fw = new FileWriter("rows.json");
    final PrintWriter writer = new PrintWriter(new BufferedWriter(fw));

    String content = client.post(pageRequestBody);
    while (hasMoreData(content)) {
      client.close();

      client = new XataClient();
      final String cursorId = cursorID(content);
      dumpRecordsToFile(writer, content);

      pageRequestBody = "{\n" +
        "  \"page\": {\n" +
        "    \"size\": 200,\n" +
        "    \"after\": \"" + cursorId + "\"" +
        "   }" +
        "}";
      content = client.post(pageRequestBody);
    }
    dumpRecordsToFile(writer, content);
    client.close();

    writer.close();
    System.out.println("Terminated");
  }

  private static void dumpRecordsToFile(PrintWriter writer, String content) {
    final String recordsJson = retrieveRecords(content);
    final String[] records = recordsJson.split("\\}\\},\\{");
    int pos = 0;
    for (String record : records) {
      if (pos == 0) {
        record += "}}";
      } else {
        if (pos == records.length - 1) {
          record = "{" + record;
        } else {
          record = "{" + record + "}}";
        }
      }
      record = record.replace(",\"xata\":{\"version\":0}", "")
          .replace(",\"xata\":{\"version\":1}", "");

      writer.println(record);
      pos ++;
    }
  }

  private static String retrieveRecords(String xataJsonResponse) {
    Pattern p = Pattern.compile("\"records\":\\[(.*?)\\]");
    Matcher m = p.matcher(xataJsonResponse);
    if (m.find()) {
      return m.group(1);
    } else {
      return null;
    }
  }

  private static boolean hasMoreData(String xataJsonResponse) {
    return xataJsonResponse.contains("\"more\":true}");
  }

  private static String cursorID(String xataJsonResponse) {
    Pattern p = Pattern.compile("\"cursor\":\"(.*?)\"");
    Matcher m = p.matcher(xataJsonResponse);
    if (m.find()) {
      return m.group(1);
    } else {
      return null;
    }
  }


  private static StringBuffer reaBody(HttpURLConnection con) throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
    String inputLine;
    StringBuffer content = new StringBuffer();
    while ((inputLine = in.readLine()) != null) {
      content.append(inputLine);
    }
    in.close();
    return content;
  }
}
