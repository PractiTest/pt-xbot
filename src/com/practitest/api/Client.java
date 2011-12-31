package com.practitest.api;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.ProxySelectorRoutePlanner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.math.BigInteger;
import java.net.ProxySelector;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author stask.
 */
public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    private static final DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();

    private String serverURL;
    private String apiKey;
    private String apiSecretKey;
    private String clientId;
    private String version;

    private DefaultHttpClient httpClient;

    public Client(String serverURL, String apiKey, String apiSecretKey, String clientId, String version) {
        if (serverURL.endsWith("/") || serverURL.endsWith("\\"))
            this.serverURL = serverURL.substring(0, serverURL.length() - 1);
        else
            this.serverURL = serverURL;
        this.apiKey = apiKey;
        this.apiSecretKey = apiSecretKey;
        this.clientId = clientId;
        this.version = version;
    }

    public synchronized boolean validate() {
        // TODO: add some dummy action to the server.
        //       right now we just check that there is no authentication error
        String url = constructURL("validate").toString();
        HttpGet getMethod = new HttpGet(url);
        try {
            setAuthenticationParameters(getMethod);
            HttpResponse response = getHTTPClient().execute(getMethod);
            return response.getStatusLine().getStatusCode() != HttpStatus.SC_INTERNAL_SERVER_ERROR;
        } catch (Throwable e) {
            return false;
        }
    }

    public synchronized Task nextTask() throws Exception {
        String url = constructURL("next_test").toString();
        Document taskDocument = null;
        HttpGet getMethod = new HttpGet(url);
        setAuthenticationParameters(getMethod);
        HttpResponse response = getHTTPClient().execute(getMethod);
        switch (response.getStatusLine().getStatusCode()) {
            case HttpStatus.SC_OK:
                logger.fine(response.toString());
                taskDocument = documentFactory.newDocumentBuilder().parse(response.getEntity().getContent());
                break;
            case HttpStatus.SC_INTERNAL_SERVER_ERROR:
                generateApiException(response);
                break;
            default:
                logger.severe("Remote call failed: " + response.toString());
                break;
        }
        return parseTaskDocument(taskDocument);
    }

    public synchronized String uploadResult(TaskResult result) throws Exception {
        StringBuilder urlBuilder = constructURL("upload_test_result");
        urlBuilder.append("&request[instance_id]=").append(result.getInstanceId());
        urlBuilder.append("&request[exit_code]=").append(result.getExitCode());
        HttpPost postMethod = new HttpPost(urlBuilder.toString());
        setAuthenticationParameters(postMethod);
//        List<Part> parts = new LinkedList<Part>();
        // TODO: re-enable attachments uploading
        //       there is a bug in rails 3.0.x: we cannot post both XML and file attachments.
        //       it's fixed in 3.1
//        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
//            for (int i = 0; i < result.getFiles().size(); ++i) {
//                File file = result.getFiles().get(i);
//                parts.add(new FilePart("result[" + file.getName() + "]", file));
//            }
//        }
//        if (!parts.isEmpty())
//            postMethod.setRequestEntity(new MultipartRequestEntity(parts.toArray(new Part[parts.size()]), postMethod.getParams()));
        HttpResponse response = getHTTPClient().execute(postMethod);
        switch (response.getStatusLine().getStatusCode()) {
            case HttpStatus.SC_OK:
                // OK
            case HttpStatus.SC_INTERNAL_SERVER_ERROR:
                generateApiException(response);
                break;
            default:
                logger.severe("Remote call failed: " + response.toString());
        }
        return urlBuilder.toString();
    }

    private Task parseTaskDocument(Document taskDocument) {
        Task task = null;
        NodeList elements = taskDocument.getElementsByTagName("instance");
        if (elements.getLength() > 0) {
            // we're only taking first item
            Element taskElement = (Element) elements.item(0);
            task = new Task(
                    taskElement.getAttribute("id"),
                    getInsideText(taskElement, "description"),
                    getInsideText(taskElement, "path-to-application"),
                    getInsideText(taskElement, "path-to-results"),
                    Integer.parseInt(taskElement.getAttribute("num_of_files_to_upload")),
                    Integer.parseInt(taskElement.getAttribute("timeout_in_seconds")));
        }
        return task;
    }

    private void generateApiException(HttpResponse response) throws Exception {
        NodeList errorElements = documentFactory.newDocumentBuilder().parse(response.getEntity().getContent()).getElementsByTagName("error");
        if (errorElements.getLength() > 0)
            throw new APIException(errorElements.item(0).getTextContent());
        else
            throw new Exception("Remote call failed: " + response.getStatusLine().toString());
    }

    private String getInsideText(Element e, String tagName) {
        return e.getElementsByTagName(tagName).item(0).getTextContent();
    }

    private synchronized HttpClient getHTTPClient() {
        if (httpClient == null) {
            httpClient = new DefaultHttpClient();
            ProxySelectorRoutePlanner routePlanner = new ProxySelectorRoutePlanner(
                    httpClient.getConnectionManager().getSchemeRegistry(),
                    ProxySelector.getDefault());
            httpClient.setRoutePlanner(routePlanner);
        }
        return httpClient;
    }

    private StringBuilder constructURL(String command) {
        StringBuilder sb = new StringBuilder();
        return sb.append(serverURL).
                append("/api/automated_tests/").append(command).append(".xml?clientId=").append(clientId).
                append("&xbot_version=").append(version);
    }

    private String createSignature(long timestamp) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        sb.append(apiKey).append(apiSecretKey).append(timestamp);
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(sb.toString().getBytes());
        return String.format("%1$032x", new BigInteger(1, digest.digest()));
    }

    private void setAuthenticationParameters(HttpRequestBase request) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        long timestamp = new Date().getTime();
        sb.append("custom api_key=").append(apiKey).
                append(", signature=").append(createSignature(timestamp)).
                append(", ts=").append(timestamp);
        request.setHeader("Authorization", sb.toString());
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
            return output;
        }
    }
}
