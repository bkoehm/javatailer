/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package software.javatailer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test the TailerThread.
 * 
 * @author Brian Koehmstedt
 */
public class TailerTest {
    private Logger log = LoggerFactory.getLogger(TailerTest.class);

    private final String sampleFileName = "sampleFile.txt";
    private TailerThread tailer;

    // as file data is received, the callback adds it to this list
    private LinkedList<String> received;
    private int underThresholdTruncateEvents;
    private int createEvents;
    private int deleteEvents;

    @Before
    public void setUp() throws Exception {
        received = new LinkedList<String>();
        underThresholdTruncateEvents = 0;
        createEvents = 0;
        deleteEvents = 0;

        /**
         * Start up the TailerThread that monitors our sampleFile. As data is
         * added to the sampleFile in this test, the callback receives the data
         * from the TailerThread and the callbacks adds the data to the received
         * list.
         */
        tailer = new TailerThread(new TailerCallback() {
            @Override
            public void createEvent(Path file) {
                createEvents++;
            }

            @Override
            public void deleteEvent(Path file) {
                deleteEvents++;
            }

            @Override
            public void truncateEvent(Path file, boolean underThreshold) {
                if (underThreshold)
                    underThresholdTruncateEvents++;
            }

            @Override
            public void receiveEvent(Path file, byte[] data) {
                if (data != null) {
                    for (String str : new String(data).split("\\n")) {
                        if (str.length() > 0) {
                            received.add(str);
                        }
                    }
                }
            }

            @Override
            public void callbackRuntimeException(String methodName, RuntimeException e) {
                log.error("bad callback", e);
            }
        }, sampleFileName);

        tailer.start();
        if (!tailer.waitForStart(2000)) {
            throw new RuntimeException("TailerThread didn't start up within 2s");
        } else {
            log.info("Tailer thread started");
        }
    }

    @After
    public void tearDown() throws Exception {
        tailer.doStop();
        tailer.interrupt();
        for (int i = 0; i < 10 && tailer.isAlive(); i++) {
            Thread.sleep(100);
        }
        if (tailer.isAlive()) {
            log.warn("Couldn't stop TailerThread");
        } else {
            log.info("Tailer thread stopped");
        }
    }

    /**
     * Test TailerThread by adding sample lines to a test file and having the
     * callback add to our received list, which can be asserted on by the test.
     * 
     * @throws InterruptedException
     * @throws IOException
     */
    @Test
    public void testTailerThread() throws InterruptedException, IOException {
        File file = new File(sampleFileName);
        file.deleteOnExit();

        // create the file with initially one line
        PrintStream ps = new PrintStream(new FileOutputStream(file));
        try {
            ps.println("test line 1");
        } finally {
            ps.close();
        }

        // wait for up to 5s for the results to come in
        assertTrue(waitForResults(WaitForType.CREATE, 1, 5000));
        assertTrue(waitForResults(WaitForType.RECEIVED, 1, 5000));

        // append to the file
        ps = new PrintStream(new FileOutputStream(file, true));
        try {
            ps.println("test line 2");
            ps.println("test line 3");
        } finally {
            ps.close();
        }

        // wait for up to 5s for the results to come in
        assertTrue(waitForResults(WaitForType.RECEIVED, 3, 5000));

        // truncate the file
        FileOutputStream fos = new FileOutputStream(file, true);
        FileChannel fc = fos.getChannel();
        ps = new PrintStream(fos);
        try {
            assertTrue(fc.truncate(0) != null);
            fc.force(true);
            ps.println("at4");
            ps.println("at5");
            fc.force(true);
        } finally {
            ps.close();
            fc.close();
            fos.close();
        }

        // wait for the truncate to be detected
        assertTrue(waitForResults(WaitForType.TRUNCATE, 1, 5000));
        // wait for the 2 new lines to be detected
        assertTrue(waitForResults(WaitForType.RECEIVED, 5, 5000));

        // delete and recreate the file quickly
        file.delete();
        ps = new PrintStream(new FileOutputStream(file));
        try {
            ps.println("test line 6");
        } finally {
            ps.close();
        }

        assertTrue(waitForResults(WaitForType.DELETE, 1, 5000));
        assertTrue(waitForResults(WaitForType.CREATE, 2, 5000));
        assertTrue(waitForResults(WaitForType.RECEIVED, 6, 5000));

        assertEquals(received.get(0), "test line 1");
        assertEquals(received.get(1), "test line 2");
        assertEquals(received.get(2), "test line 3");
        assertEquals(received.get(3), "at4");
        assertEquals(received.get(4), "at5");
        assertEquals(received.get(5), "test line 6");
    }

    private static enum WaitForType {
        RECEIVED, TRUNCATE, CREATE, DELETE
    };

    private boolean waitForResults(WaitForType type, int size, int timeoutMillis) {
        boolean doBreak = false;
        for (int totalMillis = 0; totalMillis < timeoutMillis; totalMillis += 100) {
            switch (type) {
            case RECEIVED:
                if (received.size() == size)
                    doBreak = true;
                break;
            case TRUNCATE:
                if (underThresholdTruncateEvents == size)
                    doBreak = true;
                break;
            case CREATE:
                if (createEvents == size)
                    doBreak = true;
                break;
            case DELETE:
                if (deleteEvents == size)
                    doBreak = true;
                break;
            default:
                throw new RuntimeException("Unknown type: " + type);
            }

            if (doBreak)
                break;

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }

        }
        return doBreak;
    }
}
