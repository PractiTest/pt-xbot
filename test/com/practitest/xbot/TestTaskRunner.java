package com.practitest.xbot;
/**
 * @author stask.
 */

import com.practitest.api.Client;
import junit.framework.TestCase;

import java.io.File;

public class TestTaskRunner extends TestCase {
    private static String DUMMY_SCRIPT = new File("./etc/dummyTask10.sh").getAbsolutePath();
    public void testRunTimeout() throws Exception {
        Client.Task task = new Client.Task("dummy", "dummy", DUMMY_SCRIPT, "etc", 2, 2);
        Main.TaskRunner taskRunner = new DummyMain().createTaskRunner(task);
        Thread taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.start();
        taskRunnerThread.join();
        assertTrue("Not timed out", taskRunner.isTimedOut());
    }

    public void testRunNoTimeout() throws Exception {
        Client.Task task = new Client.Task("dummy", "dummy", DUMMY_SCRIPT, "etc", 2, 12);
        Main.TaskRunner taskRunner = new DummyMain().createTaskRunner(task);
        Thread taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.start();
        taskRunnerThread.join();
        assertFalse("Timed out", taskRunner.isTimedOut());
        assertEquals(0, taskRunner.getExitCode());
    }

    private static class DummyMain extends Main {
        public DummyMain() throws Exception {
            super(-1, true);
        }

        public TaskRunner createTaskRunner(Client.Task task) {
            return new TaskRunner(task);
        }
    }
}