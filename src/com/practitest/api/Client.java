package com.practitest.api;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author stask.
 */
public class Client {
  private static final Logger logger = Logger.getLogger(Client.class.getName());

  private static final int DEFAULT_CONNECTION_TIMEOUT = 5000;

  private static final JsonFactory jsonFactory = new JsonFactory();

  private String serverURL;
  private String apiKey;
  private String apiSecretKey;
  private String clientId;
  private String proxyHost;
  private String proxyPort;
  private String version;

  private HttpClient httpClient;

  public Client(String serverURL, String apiKey, String apiSecretKey, String clientId,
                String proxyHost, String proxyPort, String version) {
    if (serverURL.endsWith("/") || serverURL.endsWith("\\"))
      this.serverURL = serverURL.substring(0, serverURL.length() - 1);
    else
      this.serverURL = serverURL;
    this.apiKey = apiKey;
    this.apiSecretKey = apiSecretKey;
    this.clientId = clientId;
    this.proxyHost = proxyHost;
    this.proxyPort = proxyPort;
    this.version = version;
  }

  public Task nextTask() throws Exception {
    String url = constructURL("next_test").toString();
    GetMethod getMethod = new GetMethod(url);
    setAuthenticationParameters(getMethod);
    try {
      int httpResult = getHTTPClient().executeMethod(getMethod);
      if (httpResult == HttpStatus.SC_OK) {
        return parseTaskDocument(getMethod.getResponseBodyAsStream());
      } else if (httpResult == HttpStatus.SC_INTERNAL_SERVER_ERROR)
        generateApiException(getMethod);
      else
        logger.severe("Remote call failed: " + getMethod.getStatusLine().toString());
    } finally {
      getMethod.releaseConnection();
    }
    return null;
  }

  public String uploadResult(TaskResult result) throws Exception {
    StringBuilder urlBuilder = constructURL("upload_test_result");
    urlBuilder.append("&instance_id=").append(result.getInstanceId());
    urlBuilder.append("&exit_code=").append(result.getExitCode());
    urlBuilder.append("&result=").append(URLEncoder.encode(result.getOutput(), "UTF-8"));
    PostMethod postMethod = new PostMethod(urlBuilder.toString());
    setAuthenticationParameters(postMethod);
    if (result.getFiles() != null && !result.getFiles().isEmpty()) {
      List<Part> parts = new LinkedList<Part>();
      for (File file : result.getFiles())
        parts.add(new FilePart("result_files[" + file.getName() + "]", file));
      postMethod.setRequestEntity(new MultipartRequestEntity(
                                                             parts.toArray(new Part[parts.size()]),
                                                             postMethod.getParams()
                                                             ));
    }
    try {
      int httpResult = getHTTPClient().executeMethod(postMethod);
      if (httpResult == HttpStatus.SC_INTERNAL_SERVER_ERROR)
        generateApiException(postMethod);
      else if (httpResult != HttpStatus.SC_OK) {
        logger.severe("Remote call failed: " + postMethod.getStatusLine().toString());
      }
    } finally {
      postMethod.releaseConnection();
    }
    return urlBuilder.toString();
  }

  private Task parseTaskDocument(InputStream stream) throws IOException {
    JsonNode rootNode = (new ObjectMapper(jsonFactory)).readTree(stream);
    if (rootNode.path("instance").isMissingNode())
      return null;
    return new Task(rootNode.path("instance").path("id").asText(),
                    "Test id:" + rootNode.path("test").path("id").asText() +
                    "Suite:" + rootNode.path("testSet").path("name").asText(),
                    rootNode.path("test").path("path_to_application").asText(),
                    rootNode.path("test").path("path_to_results").asText(),
                    rootNode.path("test").path("num_of_files_to_upload").asInt(),
                    rootNode.path("instance").path("timeout_in_seconds").asInt());
  }

  private void generateApiException(HttpMethodBase mm) throws Exception {
    throw new Exception("Remote call Failed Error #" + HttpStatus.SC_INTERNAL_SERVER_ERROR + ":" + mm.getResponseBodyAsString());
  }

  private synchronized HttpClient getHTTPClient() {
    if (httpClient == null) {
      httpClient = new HttpClient();
      if (!proxyHost.isEmpty()) {
        httpClient.getHostConfiguration().setProxy(proxyHost, Integer.parseInt(proxyPort));
      }
      httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT);
    }
    return httpClient;
  }

  private StringBuilder constructURL(String command) {
    StringBuilder sb = new StringBuilder();
    return sb.append(serverURL).
      append("/api/automated_tests/").append(command).append(".json?client_id=").append(clientId).
      append("&xbot_version=").append(version);
  }

  private String createSignature(long timestamp) throws NoSuchAlgorithmException {
    StringBuilder sb = new StringBuilder();
    sb.append(apiKey).append(apiSecretKey).append(timestamp);
    MessageDigest digest = MessageDigest.getInstance("MD5");
    digest.update(sb.toString().getBytes());
    return String.format("%1$032x", new BigInteger(1, digest.digest()));
  }

  private void setAuthenticationParameters(HttpMethod request) throws NoSuchAlgorithmException {
    StringBuilder sb = new StringBuilder();
    long timestamp = new Date().getTime();
    sb.append("custom api_key=").append(apiKey).
      append(", signature=").append(createSignature(timestamp)).
      append(", ts=").append(timestamp);
    request.setRequestHeader("Authorization", sb.toString());
  }

  public static class Task {
    private final String instanceId;
    private final String description;
    private final String pathToTestApplication;
    private final String pathToTestResults;
    private final int numOfFilesToUpload;
    private final int timeoutInSeconds;

    public Task(String instanceId,
                String description,
                String pathToTestApplication,
                String pathToTestResults,
                int numOfFilesToUpload,
                int timeoutInSeconds) {
      this.instanceId = instanceId;
      this.description = description;
      this.pathToTestApplication = pathToTestApplication;
      this.pathToTestResults = pathToTestResults;
      this.numOfFilesToUpload = numOfFilesToUpload;
      this.timeoutInSeconds = timeoutInSeconds;
    }

    public String getInstanceId() {
      return instanceId;
    }

    public String getDescription() {
      return description;
    }

    public String getPathToTestApplication() {
      return pathToTestApplication;
    }

    public String getPathToTestResults() {
      return pathToTestResults;
    }

    public int getNumOfFilesToUpload() {
      return numOfFilesToUpload;
    }

    public int getTimeoutInSeconds() {
      return timeoutInSeconds;
    }
  }

  public class APIException extends Exception {
    public APIException(String s) {
      super(s);
    }
  }

  public static class TaskResult {
    private final String instanceId;
    private final int exitCode;
    private final List<File> files;
    private final String output;

    public TaskResult(String instanceId, int exitCode, List<File> files, String output) {
      this.instanceId = instanceId;
      this.exitCode = exitCode;
      this.files = files;
      this.output = output;
    }

    public String getInstanceId() {
      return instanceId;
    }

    public int getExitCode() {
      return exitCode;
    }

    public List<File> getFiles() {
      return files;
    }

    public String getOutput() {
      return getOutput(255);
    }

    public String getOutput(int maxLength) {
      return output.length() > maxLength ? output.substring(0, maxLength - 6) + "<...>" : output;
    }
  }
}
