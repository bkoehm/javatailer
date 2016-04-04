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
import java.io.PrintStream;
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

    @Before
    public void setUp() throws Exception {
        received = new LinkedList<String>();

        /**
         * Start up the TailerThread that monitors our sampleFile. As data is
         * added to the sampleFile in this test, the callback receives the data
         * from the TailerThread and the callbacks adds the data to the received
         * list.
         */
        tailer = new TailerThread(new TailerCallback() {
            @Override
            public void createEvent(Path file) {
            }

            @Override
            public void deleteEvent(Path file) {
            }

            @Override
            public void truncateEvent(Path file) {
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
     * @throws FileNotFoundException
     * @throws InterruptedException
     */
    @Test
    public void testTailerThread() throws FileNotFoundException, InterruptedException {
        File file = new File(sampleFileName);
        file.deleteOnExit();

        // create the file with initially one line
        PrintStream ps = new PrintStream(new FileOutputStream(file));
        try {
            ps.println("test line 1");
        } finally {
            ps.close();
        }

        // wait for up to 2s for the results to come in
        for (int i = 0; i < 20; i++) {
            if (received.size() == 1) {
                break;
            } else {
                Thread.sleep(100);
            }
        }
        assertTrue(received.size() == 1);

        // append to the file
        ps = new PrintStream(new FileOutputStream(file, true));
        try {
            ps.println("test line 2");
            ps.println("test line 3");
        } finally {
            ps.close();
        }

        // wait for up to 2s for the results to come in
        for (int i = 0; i < 20; i++) {
            if (received.size() == 3) {
                break;
            } else {
                Thread.sleep(100);
            }
        }

        assertTrue(received.size() == 3);
        assertEquals(received.get(0), "test line 1");
        assertEquals(received.get(1), "test line 2");
        assertEquals(received.get(2), "test line 3");
    }
}
