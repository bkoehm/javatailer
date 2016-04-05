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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread that monitors a file for appended data much like how the Unix "tail"
 * command works. This implementation uses the Java NIO.2 WatchService to detect
 * file changes.
 * <p/>
 * An implementation of the @{link TailerCallback} interface is passed to the
 * constructor. This callback mechanism is the way that a user gets back events
 * from this TailerThread. Of most interest is @{link
 * TailerCallback#receiveEvent} which is the callback method that gets called
 * when data is appended to the monitored file.
 * <p/>
 * If the TailerThread is configured to monitor an existing file with data
 * already in it, the existing data will not be sent to any callbacks. This
 * implementation is meant to detect appended data.
 * 
 * @author Brian Koehmstedt
 */
public class TailerThread extends Thread {
    private static final Logger LOG = LoggerFactory.getLogger(TailerThread.class);

    // options that can be set
    private final TailerCallback callback;
    private final Path path;

    // internally-managed
    /**
     * Set when a fatal occurs within the TailerThread that causes it to exit.
     */
    private Exception exception;

    /**
     * Indicates when the TailerThread has started monitoring file events.
     */
    private volatile boolean isStarted;

    /**
     * A flag that, when true, indicates the TailerThread should exit.
     */
    private volatile boolean doStop;

    /**
     * The size of the file on the last read event.
     */
    private long lastSize;

    /**
     * The FileChannel for the monitored file.
     */
    private FileChannel fc;

    /**
     * The WatchService where monitored file directories are registered for file
     * change events. Note that since only directories can be monitored with the
     * WatchService, the file's parent directory is what gets registered for
     * events.
     */
    private WatchService watchService;

    /**
     * Constructor where the file is relative to the current directory. TODO:
     * This is likely to be removed or modified. Use other constructor.
     * 
     * @param callback Caller implements the @{link TailerCallback} interface to
     *            receive tailer events.
     * @param file The filename to monitor. This filename is relative to the
     *            current directory.
     */
    public TailerThread(TailerCallback callback, String file) {
        this(callback, ".", file);
    }

    /**
     * Constructor where a directory and fileName in that directory is to be
     * monitored for changes.
     * 
     * @param callback Caller implements the @{link TailerCallback} interface to
     *            receive tailer events.
     * @param directory The directory containing the fileName to monitor.
     * @param fileName The fileName in the given directory to monitor.
     */
    public TailerThread(TailerCallback callback, String directory, String fileName) {
        this.callback = callback;
        this.path = FileSystems.getDefault().getPath(directory, fileName);
    }

    /**
     * Blocks until the TailerThread has started, has exited before it has
     * finished starting (e.g., due to a fatal exception), or has timed out
     * waiting for the thread to finish starting. The timeout period is
     * defaulted to 2 seconds.
     * 
     * @return true for started successfully or false when something has gone
     *         wrong during the start-up procedure. @see #getException() if a
     *         failure occurs.
     * @throws InterruptedException
     */
    public boolean waitForStart() throws InterruptedException {
        return waitForStart(2000);
    }

    /**
     * Blocks until the TailerThread has started, has exited before it has
     * finished starting (e.g., due to a fatal exception), or has timed out
     * waiting for the thread to finish starting.
     * 
     * @return true for started successfully or false when something has gone
     *         wrong during the start-up procedure. @see #getException() if a
     *         failure occurs.
     * @throws InterruptedException
     */
    public boolean waitForStart(int timeoutMillis) throws InterruptedException {
        for (int millis = 0; millis < timeoutMillis; millis += 100) {
            if (isStarted() || exception != null) {
                break;
            }
            Thread.sleep(100);
        }
        if (exception == null) {
            exception = new Exception("Timed out waiting for thread to start");
        }
        return isStarted();
    }

    /**
     * Indicates whether the thread start-up procedure has completed and whether
     * monitoring of files has begun. File events that occur before this returns
     * true will not be caught.
     * 
     * @return true to indicate the thread start-up procedure has completed or
     *         false if it has not. @see #waitForStart(), #getException()
     */
    public boolean isStarted() {
        return isStarted;
    }

    /**
     * Create the WatchService for the default filesystem. The WatchService is
     * responsible for watching directories for changed file events.
     * 
     * @return The WatchService object.
     * @throws IOException
     */
    protected WatchService createWatchService() throws IOException {
        return FileSystems.getDefault().newWatchService();
    }

    /**
     * Called when the thread is started or restarted.
     * 
     * @throws IOException
     */
    private void resetForRun() throws IOException {
        this.isStarted = false;
        this.doStop = false;
        this.exception = null;
        this.lastSize = 0;
        if (fc != null) {
            try {
                fc.close();
            } catch (IOException e) {
                LOG.error("Couldn't close file " + fc + " upon re-run", e);
            }
            this.fc = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.error("Couldn't close watchService upon re-run");
            }
        }
        this.watchService = createWatchService();
    }

    /**
     * The thread entrance method. This begins the thread start-up procedure
     * and @{link isStarted()} will return true when file monitoring has begun.
     * The thread will send file change events to the configured callback
     * object.
     */
    @Override
    public void run() {
        try {
            resetForRun();

            // register this file with the watchService
            register(watchService, path);

            // file may not actually exist yet but if it does, go ahead
            // and open it now
            if (Files.exists(path) && Files.isRegularFile(path)) {
                this.lastSize = Files.size(path);
                fc = open(path);
                // Seek to end of file. With tail, we want only data that is
                // appended after opening.
                fc.position(lastSize);
                LOG.debug("Opened at start and moved position to " + lastSize);
            }

            // isStarted must be marked after register().
            this.isStarted = true;
            LOG.debug("TailerThread marked as started");

            while (!doStop) {
                try {
                    LOG.debug("about to call watchService.take()");
                    WatchKey key = watchService.take();
                    LOG.debug("take() returned");
                    try {
                        Path watchedDirectory = (Path)key.watchable();
                        for (WatchEvent<?> watchEventNoParam : key.pollEvents()) {
                            @SuppressWarnings("unchecked")
                            WatchEvent<Path> watchEvent = (WatchEvent<Path>)watchEventNoParam;
                            LOG.debug("event " + watchEvent.kind() + " for " + watchEvent.context());
                            Path watchedFile = watchedDirectory.resolve(watchEvent.context());
                            LOG.debug("watchedFile = " + watchedFile);
                            if (Files.isSameFile(watchedFile, path)) {
                                LOG.debug("does equal");
                                event(watchEvent.kind(), watchedFile);
                            } else {
                                LOG.debug("does not equal");
                            }
                        }
                    }
                    finally {
                        key.reset();
                    }
                } catch (InterruptedException e) {
                    // ignore: caller will set doStop if they want this thread
                    // to exit
                }
            }
        } catch (Exception e) {
            LOG.error("TailMonitorThread failed", e);
            // Set the fatal exception for the thread.
            this.exception = e;
        } finally {
            LOG.debug("doStop was called.  Exiting.");
            if (fc != null && fc.isOpen()) {
                try {
                    fc.close();
                } catch (IOException e) {
                    LOG.error("Couldn't close file " + fc + " before thread exit", e);
                }
            }
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    LOG.error("Couldn't close watchService before thread exit", e);
                }
            }
            this.isStarted = false;
        }
    }

    /**
     * Called when a file change event is detected by the watch service. An
     * event can be a CREATE, DELETE or MODIFY.
     * 
     * @param kind Indicates what kind of event: CREATE, DELETE or MODIFY.
     * @param path The Path object for the file that was changed.
     * @throws IOException
     */
    protected void event(WatchEvent.Kind<Path> kind, Path path) throws IOException {
        if (kind.equals(StandardWatchEventKinds.ENTRY_CREATE)) {
            if (!Files.isRegularFile(path)) {
                throw new RuntimeException(path + " is not a regular file or can't be opened for reading");
            }
            if (fc != null) {
                // in theory this doesn't happen because the delete event should
                // come first, but just in case
                try {
                    fc.close();
                } catch (IOException e) {
                    LOG.error("Couldn't close file " + fc + " on recreate");
                }
                fc = null;
            }
            this.lastSize = 0;
            fc = open(path);

            try {
                callback.createEvent(path);
            } catch (RuntimeException e) {
                reportCallbackException("createEvent", e);
            }
        }

        if (kind.equals(StandardWatchEventKinds.ENTRY_CREATE) || kind.equals(StandardWatchEventKinds.ENTRY_MODIFY)) {
            if (fc == null) {
                throw new RuntimeException("It is not expected that the file channel be null here.");
            }

            long currentSize = lastSize;
            // try up to 2 seconds to wait for the file metadata to catch up
            // with the MODIFY event
            for (int i = 0; i < 20; i++) {
                currentSize = Files.size(path);
                if (currentSize != lastSize) {
                    break;
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                }
            }
            if (currentSize > lastSize) {
                // Read from lastSize byte to currentSize byte
                long attemptedReadBytes = currentSize - lastSize;
                ByteBuffer buf = ByteBuffer.allocate(Long.valueOf(attemptedReadBytes).intValue());
                int bytesRead = -1;
                try {
                    bytesRead = fc.read(buf);
                } catch (IOException e) {
                    LOG.error("read error", e);
                }
                LOG.debug("bytesRead = " + bytesRead);
                if (bytesRead == -1) {
                    LOG.debug("closing");
                    fc.close();
                    this.lastSize = 0;
                    fc = null;
                    // reopen
                    fc = open(path);
                    return;
                } else if (bytesRead > 0) {
                    LOG.debug("inData = " + new String(buf.array()));
                    this.lastSize += bytesRead;
                    try {
                        callback.receiveEvent(path, buf.array());
                    } catch (RuntimeException e) {
                        reportCallbackException("receiveEvent", e);
                    }
                    return;
                } else {
                    LOG.warn("Why 0 bytes read?");
                }
            } else {
                LOG.warn("currentSize is not greater than the last read size.  currentSize=" + currentSize + ", lastSize=" + lastSize);
            }

            this.lastSize = currentSize;
        } else if (kind.equals(StandardWatchEventKinds.ENTRY_DELETE)) {
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException e) {
                    LOG.error("Couldn't close file " + fc + " upon delete event", e);
                }
                fc = null;
            }
            this.lastSize = 0;

            try {
                callback.deleteEvent(path);
            } catch (RuntimeException e) {
                reportCallbackException("deleteEvent", e);
            }
        }
    }

    /**
     * This will return what exception occurred if the the thread has to exit
     * due to a fatal error.
     * 
     * @return The exception that caused the thread to exit.
     */
    public Exception getException() {
        return exception;
    }

    /**
     * Mark the thread to stop. This should be followed by a call to
     * {@link #interrupt()} to interrupt any blocked calls, such as the
     * watchService call that waits for events.
     */
    public void doStop() {
        this.doStop = true;
    }

    /**
     * Open a FileChannel for a monitored file.
     * 
     * @param path The Path of the file to open a FileChannel for.
     * @return The FileChannel object.
     * @throws IOException
     */
    protected FileChannel open(Path path) throws IOException {
        FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
        LOG.debug("Opened file channel");
        return fc;
    }

    /**
     * Register a file's parent directory with the watchService. Note that the
     * WatchService can only monitor directories and not individual files, so
     * that is why we monitor the file's directory.
     * 
     * @param watchService The WatchService to register with.
     * @param path The file to monitor.
     * @return The WatchKey returned by the registration process with the
     *         WatchService.
     * @throws IOException
     */
    protected WatchKey register(WatchService watchService, Path path) throws IOException {
        WatchKey watchKey = path.getParent().register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                                                      StandardWatchEventKinds.ENTRY_MODIFY);
        LOG.debug("Registered " + path.getParent());
        return watchKey;
    }

    /**
     * This method is called to report an RuntimeException that occurred when
     * calling a callback method. Its main purpose is to call
     * {@link TailerCallback#callbackRuntimeException(String, RuntimeException)}
     * . If another RuntimeException occurs during the call to
     * callbackRuntimeException(), that exception is ignored.
     * 
     * @param callbackMethodName The callback method name that was being called
     *            when the exception occurred.
     * @param e The RuntimeException that occurred when calling the callback
     *            method.
     */
    protected void reportCallbackException(String callbackMethodName, RuntimeException e) {
        try {
            LOG.error("callback." + callbackMethodName + " threw a runtime exception", e);
            callback.callbackRuntimeException(callbackMethodName, e);
        } catch (RuntimeException e2) {
            // ignore
        }
    }
}
