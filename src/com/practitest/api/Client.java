package com.practitest.api;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
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
    private static final int XBOT_VERSION = 1;

    private static final DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();

    private String serverURL;
    private String apiKey;
    private String apiSecretKey;

    private HttpClient httpClient;

    public Client(String serverURL, String apiKey, String apiSecretKey) {
        this.serverURL = serverURL;
        this.apiKey = apiKey;
        this.apiSecretKey = apiSecretKey;
    }

    public Task nextTask() throws IOException, NoSuchAlgorithmException, ParserConfigurationException, SAXException {
        String url = constructURL("next_test").toString();
        Document taskDocument = null;
        GetMethod getMethod = new GetMethod(url);
        try {
            if (getHTTPClient().executeMethod(getMethod) != HttpStatus.SC_OK) {
                logger.severe("Remote call failed: " + getMethod.getStatusLine().toString());
            } else {
                logger.info(getMethod.getResponseBodyAsString());
                taskDocument = documentFactory.newDocumentBuilder().parse(getMethod.getResponseBodyAsStream());
            }
        } finally {
            getMethod.releaseConnection();
        }
        return parseTaskDocument(taskDocument);
    }

    public void uploadResult(TaskResult result) throws IOException, NoSuchAlgorithmException {
        StringBuilder urlBuilder = constructURL("upload_test_result");
        urlBuilder.append("&instance_id=").append(result.getInstanceId());
        urlBuilder.append("&exitCode=").append(result.getExitCode());
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
            if (getHTTPClient().executeMethod(postMethod) != HttpStatus.SC_OK) {
                logger.severe("Remote call failed: " + postMethod.getStatusLine().toString());
            }
        } finally {
            postMethod.releaseConnection();
        }
    }

    private Task parseTaskDocument(Document taskDocument) {
        Task task = null;
        NodeList elements = taskDocument.getElementsByTagName("task");
        if (elements.getLength() > 0) {
            // we're only taking first item
            Element taskElement = (Element) elements.item(0);
            task = new Task(
                    taskElement.getAttribute("instance-id"),
                    taskElement.getAttribute("path-to-application"),
                    taskElement.getAttribute("path-to-results"));
        }
        return task;
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
        return sb.append(serverURL).append("/automated_tests/").append(command).append(".xml?api_key=").
                append(apiKey).append("&signature=").append(createSignature(timestamp)).
                append("&ts=").append(timestamp).
                append("&xbot_version=").append(XBOT_VERSION);
    }

    private String createSignature(long timestamp) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        sb.append(apiKey).append(apiSecretKey).append(timestamp);
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(sb.toString().getBytes());
        return String.format("%1$032x", new BigInteger(1, digest.digest()));
    }

    public static class Task {
        private String instanceId;
        private String pathToTestApplication;
        private String pathToTestResults;

        public Task(String instanceId, String pathToTestApplication, String pathToTestResults) {
            this.instanceId = instanceId;
            this.pathToTestApplication = pathToTestApplication;
            this.pathToTestResults = pathToTestResults;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public String getPathToTestApplication() {
            return pathToTestApplication;
        }

        public String getPathToTestResults() {
            return pathToTestResults;
        }
    }

    public static class TaskResult {
        private String instanceId;
        private int exitCode;
        private List<File> files;

        public TaskResult(String instanceId, int exitCode, List<File> files) {
            this.instanceId = instanceId;
            this.exitCode = exitCode;
            this.files = files;
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
    }
}
