package com.practitest.api;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.math.BigInteger;
import java.net.MalformedURLException;
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

    private static final int DEFAULT_CONNECTION_TIMEOUT = 5000;

    private static final DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();

    private String serverURL;
    private String apiKey;
    private String apiSecretKey;
    private String clientId;
    private String version;

    private HttpClient httpClient;

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

    public Task nextTask() throws Exception {
        String url = constructURL("next_test").toString();
        Document taskDocument = null;
        GetMethod getMethod = new GetMethod(url);
        try {
            int httpResult = getHTTPClient().executeMethod(getMethod);
            if (httpResult == HttpStatus.SC_OK) {
                logger.info(getMethod.getResponseBodyAsString());
                taskDocument = documentFactory.newDocumentBuilder().parse(getMethod.getResponseBodyAsStream());
            } else if (httpResult == HttpStatus.SC_INTERNAL_SERVER_ERROR)
                generateApiException(getMethod);
            else
                logger.severe("Remote call failed: " + getMethod.getStatusLine().toString());
        } finally {
            getMethod.releaseConnection();
        }
        return parseTaskDocument(taskDocument);
    }

    public String uploadResult(TaskResult result) throws Exception {
        StringBuilder urlBuilder = constructURL("upload_test_result");
        urlBuilder.append("&instance_id=").append(result.getInstanceId());
        urlBuilder.append("&exit_code=").append(result.getExitCode());
        PostMethod postMethod = new PostMethod(urlBuilder.toString());
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            Part[] parts = new FilePart[result.getFiles().size()];
            for (int i = 0; i < result.getFiles().size(); ++i) {
                File file = result.getFiles().get(i);
                parts[i] = new FilePart("result[" + file.getName() + "]", file);
            }
            postMethod.setRequestEntity(new MultipartRequestEntity(parts, postMethod.getParams()));
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

    private void generateApiException(HttpMethodBase mm) throws Exception {
        NodeList errorElements = documentFactory.newDocumentBuilder().parse(mm.getResponseBodyAsStream()).getElementsByTagName("error");
        if (errorElements.getLength() > 0)
            throw new APIException(errorElements.item(0).getTextContent());
        else
            throw new Exception("Remote call Failed Error #" + HttpStatus.SC_INTERNAL_SERVER_ERROR + ":" + mm.getResponseBodyAsString());
    }

    private String getInsideText(Element e, String tagName) {
        return e.getElementsByTagName(tagName).item(0).getTextContent();
    }

    private synchronized HttpClient getHTTPClient() {
        if (httpClient == null) {
            httpClient = new HttpClient();
            httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT);
        }
        return httpClient;
    }

    private StringBuilder constructURL(String command) throws MalformedURLException, NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        long timestamp = new Date().getTime();
        return sb.append(serverURL).append("/api/").append(apiKey).append("/automated_tests/").append(command).
                append(".xml?signature=").append(createSignature(timestamp)).
                append("&ts=").append(timestamp).append("&client_id=").append(clientId).
                append("&xbot_version=").append(version);
    }

    private String createSignature(long timestamp) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        sb.append(apiKey).append(apiSecretKey).append(timestamp);
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(sb.toString().getBytes());
        return String.format("%1$032x", new BigInteger(1, digest.digest()));
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
