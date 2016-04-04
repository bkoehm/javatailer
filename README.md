JavaTailer monitors files for appended data much like the Unix tail command.

This implementation uses the NIO.2 `WatchService` to monitor file events and
thus version 7 or greater of Java&reg; is required.

The "tailer" runs in its own thread and utilizes a callback mechanism that
the user implements to notify of file change events.

# License

This software is licensed under the Apache License, v2.0.  See the `LICENSE`
file.  The Copyright notice is contained in the `NOTICE` file.

# Using

See `TailerTest.java` for a working example of how to instantiate the thread
and build your callback object.

Short version:

```TailerCallback callback = new TailerCallback() { ... }; // implement interface methods
TailerThread tailer = new TailerThread(callback, "/home/me", "fileToMonitor.txt");
tailer.start(); // start the thread
if (!tailer.waitForStart(2000)) {
    throw new RuntimeException("TailerThread didn't start up within 2s");
} 
```

Then add lines to `/home/me/fileToMonitor.txt` and the `receiveEvent()` in
your `TailerCallback` implementation will receive the appended lines.

To signal the thread to stop:
```tailer.doStop();
tailer.interrupt();
```

# Building

The build system is gradle.  To build:

`gradle build`

To run just the tests:

`gradle test`

To install the gradle wrapper (perhaps for your IDE):

`gradle wrapper --gradle-version 2.12`

Or, if you prefer, replace 2.12 with the latest Gradle version.

To publish to your local maven repository:
`gradle publishToMavenLocal`
