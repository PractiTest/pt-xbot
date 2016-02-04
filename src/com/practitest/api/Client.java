package com.practitest.api;

import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.SecureProtocolSocketFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.*;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
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
  private String proxyUser;
  private String proxyPassword;
  private String version;

  private HttpClient httpClient;

  public Client(String serverURL, String apiKey, String apiSecretKey, String clientId,
                String proxyHost, String proxyPort, String proxyUser, String proxyPassword, String version) {
    if (serverURL.endsWith("/") || serverURL.endsWith("\\"))
      this.serverURL = serverURL.substring(0, serverURL.length() - 1);
    else
      this.serverURL = serverURL;
    this.apiKey = apiKey;
    this.apiSecretKey = apiSecretKey;
    this.clientId = clientId;
    this.proxyHost = proxyHost;
    this.proxyPort = proxyPort;
    this.proxyUser = proxyUser;
    this.proxyPassword = proxyPassword;
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
      Protocol easyHTTPS = new Protocol("https", new EasySSLProtocolSocketFactory(), 443);
      Protocol.registerProtocol("https", easyHTTPS);

      httpClient = new HttpClient();
      if (!proxyHost.isEmpty()) {
        httpClient.getHostConfiguration().setProxy(proxyHost, Integer.parseInt(proxyPort));
      }
      if (!proxyUser.isEmpty()) {
        NTCredentials credentials = new NTCredentials(proxyUser, proxyPassword, "", "");
        httpClient.getState().setProxyCredentials(AuthScope.ANY, credentials);
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

  // adopted from http://svn.apache.org/viewvc/httpcomponents/oac.hc3x/trunk/src/contrib/org/apache/commons/httpclient/contrib/ssl/

  public static class EasyX509TrustManager implements X509TrustManager {
    private X509TrustManager standardTrustManager = null;

    /** Log object for this class */
    private static final Log LOG = LogFactory.getLog(EasyX509TrustManager.class);

    /**
     * Constructor for EasyX509TrustManager.
     */
    public EasyX509TrustManager(KeyStore keystore) throws NoSuchAlgorithmException, KeyStoreException {
      super();
      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(keystore);
      TrustManager[] trustManagers = factory.getTrustManagers();
      if (trustManagers.length == 0) {
        throw new NoSuchAlgorithmException("no trust managers found");
      }
      this.standardTrustManager = (X509TrustManager)trustManagers[0];
    }

    /**
     * @see javax.net.ssl.X509TrustManager#checkClientTrusted(X509Certificate[], String)
     */
    public void checkClientTrusted(X509Certificate[] certificates, String authType) throws CertificateException {
      standardTrustManager.checkClientTrusted(certificates, authType);
    }

    /**
     * @see javax.net.ssl.X509TrustManager#checkServerTrusted(X509Certificate[], String)
     */
    public void checkServerTrusted(X509Certificate[] certificates, String authType) throws CertificateException {
      if ((certificates != null) && LOG.isDebugEnabled()) {
        LOG.debug("Server certificate chain:");
        for (int i=0; i<certificates.length; i++) {
          LOG.debug("X509Certificate[" + i + "]=" + certificates[i]);
        }
      }
      if ((certificates != null) && (certificates.length > 0)) {
        certificates[0].checkValidity();
      } else {
        standardTrustManager.checkServerTrusted(certificates, authType);
      }
    }

    /**
     * @see X509TrustManager#getAcceptedIssuers()
     */
    public X509Certificate[] getAcceptedIssuers() {
      return this.standardTrustManager.getAcceptedIssuers();
    }
  }

  public static class EasySSLProtocolSocketFactory implements SecureProtocolSocketFactory {

    /** Log object for this class. */
    private static final Log LOG = LogFactory.getLog(EasySSLProtocolSocketFactory.class);
    private SSLContext sslContext = null;

    /**
     * Constructor for EasySSLProtocolSocketFactory.
     */
    public EasySSLProtocolSocketFactory() {
      super();
    }

    private static SSLContext createEasySSLContext() {
      try {
        SSLContext context = SSLContext.getInstance("SSL");
        context.init(
                null,
                new TrustManager[] {new EasyX509TrustManager(null)},
                null);
        return context;
      } catch (Exception e) {
        LOG.error(e.getMessage(), e);
        throw new HttpClientError(e.toString());
      }
    }

    private SSLContext getSSLContext() {
      if (this.sslContext == null) {
        this.sslContext = createEasySSLContext();
      }
      return this.sslContext;
    }

    /**
     * @see SecureProtocolSocketFactory#createSocket(String, int, InetAddress, int)
     */
    public Socket createSocket(
            String host,
            int port,
            InetAddress clientHost,
            int clientPort)
      throws IOException, UnknownHostException {
      return getSSLContext().getSocketFactory().createSocket(
              host,
              port,
              clientHost,
              clientPort);
    }

    /**
     * Attempts to get a new socket connection to the given host within the given time limit.
     * <p>
     *   To circumvent the limitations of older JREs that do not support connect timeout a
     *   controller thread is executed. The controller thread attempts to create a new socket
     *   within the given limit of time. If socket constructor does not return until the
     *   timeout expires, the controller terminates and throws an {@link ConnectTimeoutException}
     * </p>
     *
     * @param host the host name/IP
     * @param port the port of the host
     * @param localAddress the local host name/IP to bind the socket to
     * @param localPort the port on the local machine
     * @param params {@link HttpConnectionParams Http connection parameters}
     *
     * @return Socket a new socket
     *
     * @throws IOException if an I/O error occurs while creating the socket
     * @throws UnknownHostException if the IP address of the host cannot be
     * determined
     */
    public Socket createSocket(
            final String host,
            final int port,
            final InetAddress localAddress,
            final int localPort,
            final HttpConnectionParams params)
      throws IOException, UnknownHostException, ConnectionPoolTimeoutException {
      if (params == null) {
        throw new IllegalArgumentException("Parameters may not be null");
      }
      int timeout  = params.getConnectionTimeout();
      SocketFactory socketFactory = getSSLContext().getSocketFactory();
      if (timeout == 0) {
        return socketFactory.createSocket(host, port, localAddress, localPort);
      } else {
        Socket socket = socketFactory.createSocket();
        SocketAddress localAddr = new InetSocketAddress(localAddress, localPort);
        SocketAddress remoteAddr = new InetSocketAddress(host, port);
        socket.bind(localAddr);
        socket.connect(remoteAddr, timeout);
        return socket;
      }
    }

    /**
     * @see SecureProtocolSocketFactory#createSocket(String, int)
     */
    public Socket createSocket(String host, int port)
      throws IOException, UnknownHostException {
      return getSSLContext().getSocketFactory().createSocket(host, port);
    }

    /**
     * @see SecureProtocolSocketFactory#createSocket(Socket, String, int, boolean)
     */
    public Socket createSocket(
            Socket socket,
            String host,
            int port,
            boolean autoClose)
      throws IOException, UnknownHostException {
      return getSSLContext().getSocketFactory().createSocket(
              socket,
              host,
              port,
              autoClose);
    }

    public boolean equals(Object obj) {
      return ((obj != null) && obj.getClass().equals(EasySSLProtocolSocketFactory.class));
    }

    public int hashCode() {
      return EasySSLProtocolSocketFactory.class.hashCode();
    }
  }
}
