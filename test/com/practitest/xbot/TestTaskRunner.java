package com.practitest.xbot;
/**
 * @author stask.
 */

import com.practitest.api.Client;
import junit.framework.*;

public class TestTaskRunner extends TestCase {
    public void testRunTimeout() throws Exception {
        Client.Task task = new Client.Task("dummy", "dummy", "etc/dummyTask10.sh", "etc", 2, 30);
        Main.TaskRunner taskRunner = new Main.TaskRunner(task);
        Thread taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.start();
        taskRunnerThread.join();
        assertTrue("Not timed out", taskRunner.isTimedOut());
    }

    public void testRunNoTimeout() throws Exception {
        Client.Task task = new Client.Task("dummy", "dummy", "etc/dummyTask10.sh", "etc", 2, 10);
        Main.TaskRunner taskRunner = new Main.TaskRunner(task);
        Thread taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.start();
        taskRunnerThread.join();
        assertFalse("Timed out", taskRunner.isTimedOut());
        assertEquals(0, taskRunner.getExitCode());
    }
}