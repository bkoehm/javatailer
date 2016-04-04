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

import java.nio.file.Path;

/**
 * The interface that the {@link TailerThread} uses to notify of events.
 * 
 * @author Brian Koehmstedt
 */
public interface TailerCallback {
    /**
     * Notification that a create event for the file has occurred.
     * 
     * @param file The Path object for the created file.
     */
    void createEvent(Path file);

    /**
     * Notification that a delete event for the file has occurred.
     * 
     * @param file The Path object for the deleted file.
     */
    void deleteEvent(Path file);

    /**
     * Notification that a truncate event for the file has occurred. A truncate
     * event means that the file's size has shrunk.
     * 
     * @param file The Path object for the truncated file.
     */
    void truncateEvent(Path file);

    /**
     * Notification that new data has been appended to a file.
     * 
     * @param file The Path object for the appended-to file.
     * @param data The data appended to the file.
     */
    void receiveEvent(Path file, byte[] data);

    /**
     * Notification that one of the other methods in this callback object have
     * thrown a RuntimeException while being called.
     * 
     * @param methodName A string representation of the method name in this
     *            callback interface that threw the RuntimeException.
     * @param e The RuntimeException that was called by the callback method.
     */
    void callbackRuntimeException(String methodName, RuntimeException e);
}
