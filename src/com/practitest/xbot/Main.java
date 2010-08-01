package com.practitest.xbot;

import com.practitest.api.Client;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.AbstractHandler;
import org.xml.sax.SAXException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * TODO: pack everything including images in one jar.
 *
 * @author stask.
 */
public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final String NO_TRAY_ICON_PROPERTY_KEY = "com.practitest.xbot.no_tray_icon";
    private static final String LISTENING_PORT_PROPERTY_KEY = "com.practitest.xbot.listening_port";

    private static final String XBOT_TRAY_CAPTION = "PractiTest xBot";

    private static final int DEFAULT_LISTENING_PORT = 18080;
    private static final int TEST_RUNNER_DELAY = 60;

    private Image trayIconImageReady;
    private Image trayIconImageRunning;
    private Image trayIconImageError;
    private TrayIcon trayIcon;

    private int listeningPort;
    private Lock lock;
    private Condition exitCondition;
    private Server theServer;
    private AtomicReference<Client> theClient = new AtomicReference<Client>();
    private ScheduledFuture<?> testRunner;

    private String apiKey = "";
    private String apiSecretKey = "";
    private String serverURL = "";

    public Main(int listeningPort, boolean noTrayIcon) throws Exception {
        loadSettings();
        this.listeningPort = listeningPort;
        lock = new ReentrantLock();
        exitCondition = lock.newCondition();

        initializeHTTPListener();
        initializeClient();
        initializeScheduler();
        if (!noTrayIcon) {
            initializeTrayIcon();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                logger.info("In shutdown hook");
                lock.lock();
                try {
                    exitCondition.signal();
                } finally {
                    lock.unlock();
                }
            }
        }));
    }

    public void run() {
        // wait for exit signal
        lock.lock();
        try {
            exitCondition.await();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
        // stop and exit
        logger.info("Stopping the internal http server...");
        try {
            theServer.stop();
        } catch (Exception e) {
            logger.severe("Failed to stop internal http server: " + e.getMessage());
        }
        logger.info("Stopped internal http server.");
        testRunner.cancel(false);
        // wait for completion of current task
        try {
            testRunner.get();
        } catch (InterruptedException ignored) {
        } catch (CancellationException ignored) {
        } catch (ExecutionException e) {
            logger.severe("Failed to execute test: " + e.getMessage());
        }
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        boolean noTrayIcon = Boolean.parseBoolean(System.getProperty(NO_TRAY_ICON_PROPERTY_KEY, Boolean.FALSE.toString()));
        int listeningPort = Integer.parseInt(System.getProperty(LISTENING_PORT_PROPERTY_KEY, String.valueOf(DEFAULT_LISTENING_PORT)));

        Main me = new Main(listeningPort, noTrayIcon);
        me.run();
    }

    private void loadSettings() {
        File settingsFile = new File(System.getProperty("user.dir"), "xbot.properties");
        if (settingsFile.exists()) {
            try {
                Properties settings = new Properties();
                settings.load(new FileReader(settingsFile));
                serverURL = settings.getProperty("server_url");
                apiKey = settings.getProperty("api_key");
                apiSecretKey = settings.getProperty("api_secret_key");
            } catch (IOException ignore) {
            }
        }
    }

    private void saveSettings() {
        // overwrites the xbot.properties!
        Properties settings = new Properties();
        settings.setProperty("server_url", serverURL);
        settings.setProperty("api_key", apiKey);
        settings.setProperty("api_secret_key", apiSecretKey);
        try {
            settings.store(new FileWriter(new File(System.getProperty("user.dir"), "xbot.properties")),
                    "Please do not change this file manually, it'll be re-written by the application anyway.");
        } catch (IOException e) {
            logger.severe("Failed to store application settings: " + e.getMessage());
        }
    }

    private void initializeHTTPListener() throws Exception {
        theServer = new Server(listeningPort);
        theServer.setHandler(new AbstractHandler() {
            public void handle(String target,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               int dispatch) throws IOException, ServletException {
                if (target.equals("/status")) {
                    response.setContentType("text/plain");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().println("OK");
                    ((Request) request).setHandled(true);
                } else if (target.equals("/preferences")) {
                    response.setContentType("text/html");
                    response.setStatus(HttpServletResponse.SC_OK);
                    PrintWriter out = response.getWriter();
                    out.println("<html><head><title>PractiTest xBot preferences</title></head>");
                    out.println("<body><form method=\"POST\" action=\"/set_preferences\">");
                    out.println("<table style=\"width:80%;\">");
                    out.println("<caption>PractiTest xBot configuration</caption>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"server_url\">PractiTest URL:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"server_url\" name=\"server_url\" value=\"\" + serverURL + \"\" /></td>");
                    out.println("</tr>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"api_key\">API Key:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"api_key\" name=\"api_key\" value=\"\" + apiKey + \"\" /></td>");
                    out.println("</tr>");
                    out.println("<tr>");
                    out.println("<th style=\"text-align:right; width:30%;\"><label for=\"api_secret_key\">API Secret Key:</label></th>");
                    out.println("<td style=\"text-align:left; width:70%;\"><input type=\"text\" id=\"api_secret_key\" name=\"api_secret_key\" value=\"\" + apiSecretKey + \"\" /></td>");
                    out.println("</tr>");
                    out.println("<tr>");
                    out.println("<td colspan=\"2\"><input type=\"submit\" value=\"Update &rArr;\" /></td>");
                    out.println("</tr>");
                    out.println("</table>");
                    out.println("</form></body></html>");
                    ((Request) request).setHandled(true);
                } else if (target.equals("/set_preferences")) {
                    serverURL = request.getParameter("server_url");
                    apiKey = request.getParameter("api_key");
                    apiSecretKey = request.getParameter("api_secret_key");
                    saveSettings();
                    initializeClient();
                    response.sendRedirect("/preferences");
                    ((Request) request).setHandled(true);
                }
            }
        });
        theServer.start();
    }

    private void initializeTrayIcon() {
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();

            Image trayIconImageNotConfigured = Toolkit.getDefaultToolkit().getImage("images/trayNotConfigured.png");
            trayIconImageReady = Toolkit.getDefaultToolkit().getImage("images/trayReady.png");
            trayIconImageRunning = Toolkit.getDefaultToolkit().getImage("images/trayRunning.png");
            trayIconImageError = Toolkit.getDefaultToolkit().getImage("images/trayError.png");

            ActionListener exitListener = new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    logger.info("Exiting...");
                    lock.lock();
                    try {
                        exitCondition.signal();
                    } catch (IllegalMonitorStateException ignore) {
                    } finally {
                        lock.unlock();
                    }
                }
            };

            PopupMenu popup = new PopupMenu();
            MenuItem defaultItem = new MenuItem("Exit");
            defaultItem.addActionListener(exitListener);
            popup.add(defaultItem);
            MenuItem openURLItem = new MenuItem("Preferences");
            openURLItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    try {
                        Desktop.getDesktop().browse(new URI("http://localhost:" + listeningPort + "/preferences"));
                    } catch (IOException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    } catch (URISyntaxException e) {
                        logger.severe("Failed to open URL: " + e.getMessage());
                    }
                }
            });
            popup.add(openURLItem);

            trayIcon = new TrayIcon(theClient.get() != null ? trayIconImageReady : trayIconImageNotConfigured, XBOT_TRAY_CAPTION, popup);

            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip(XBOT_TRAY_CAPTION);

            try {
                tray.add(trayIcon);
            } catch (AWTException e) {
                logger.severe("TrayIcon could not be added: " + e.getMessage());
            }
        } else {
            logger.warning("System tray is not supported");
        }
    }

    private void initializeClient() {
        theClient.set(null);
        if (serverURL.isEmpty() || apiKey.isEmpty() || apiSecretKey.isEmpty()) return;
        theClient.set(new Client(serverURL, apiKey, apiSecretKey));
        if (trayIcon != null) {
            trayIcon.setImage(trayIconImageReady);
            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot is ready", TrayIcon.MessageType.INFO);
        }
    }

    private void initializeScheduler() {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        testRunner = scheduler.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                logger.info("TestRunner is awake");
                Client client = theClient.get();
                if (client != null) {
                    try {
                        Client.Task task = client.nextTask();
                        if (task != null) {
                            trayIcon.setImage(trayIconImageRunning);
                            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot is running task", TrayIcon.MessageType.INFO);
                            Process childProcess = Runtime.getRuntime().exec(task.getPathToTestApplication());
                            int exitCode = childProcess.waitFor();
                            java.util.List<File> taskResultFiles = null;
                            File taskResultFilesDir = new File(task.getPathToTestResults());
                            if (taskResultFilesDir.isDirectory()) {
                                taskResultFiles = Arrays.asList(taskResultFilesDir.listFiles(new FileFilter() {
                                    public boolean accept(File file) {
                                        return file.isFile();
                                    }
                                }));
                            }
                            client.uploadResult(new Client.TaskResult(task.getId(), task.getProjectId(), exitCode, taskResultFiles));
                            trayIcon.setImage(trayIconImageReady);
                            trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot finished running task, ready for the next one", TrayIcon.MessageType.INFO);
                        }
                    } catch (IOException e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Error occurred during communication with PractiTest server: " + e.getMessage());
                    } catch (NoSuchAlgorithmException e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Error occurred during communication with PractiTest server: " + e.getMessage());
                    } catch (ParserConfigurationException e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Error occurred during communication with PractiTest server: " + e.getMessage());
                    } catch (SAXException e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Error occurred during communication with PractiTest server: " + e.getMessage());
                    } catch (InterruptedException e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Error occurred during execution of task: " + e.getMessage());
                    } catch (Throwable e) {
                        trayIcon.setImage(trayIconImageError);
                        trayIcon.displayMessage(XBOT_TRAY_CAPTION, "PractiTest xBot failed to run task: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                        logger.severe("Unhandled exception: " + e.getMessage());
                    }
                } else {
                    logger.warning("PractiTest client is not yet configured");
                }
                logger.info("TestRunner finished, going to sleep.");
            }
        }, TEST_RUNNER_DELAY, TEST_RUNNER_DELAY, TimeUnit.SECONDS);
    }
}
