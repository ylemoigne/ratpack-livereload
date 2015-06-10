/*
 * Copyright 2015 Yann Le Moigne
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.javatic.ratpack.livereload;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.server.RatpackServer;
import ratpack.server.ServerConfig;
import ratpack.websocket.*;
import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.util.async.Async;
import rx.util.async.StoppableObservable;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiveReloadServer {
    private final static Logger LOGGER = LoggerFactory.getLogger(LiveReloadServer.class);

    private StoppableObservable<String> fileWatchStream;
    private RatpackServer ratpackServer;

    public LiveReloadServer(Path baseDir) {
        fileWatchStream = Async.<String>runAsync(Schedulers.io(), (observer, subscription) -> {
            Watcher watcher = new Watcher(baseDir, observer, subscription);
            watcher.run();
        });

        LiveReloadWebSocketHandler liveReloadWebSocketHandler = new LiveReloadWebSocketHandler(fileWatchStream);
        try {
            ratpackServer = RatpackServer.of(builder -> builder
                    .serverConfig(ServerConfig.findBaseDir("dev/ratpack.properties").development(true).port(35729))
                    .handlers(chain -> {
                        chain.get("livereload.js", ctx -> {
                            ctx.getResponse().sendFile(ctx.get(ServerConfig.class).getBaseDir().file("livereload.js"));
                        });
                        chain.get("livereload", ctx -> WebSockets.websocket(ctx, liveReloadWebSocketHandler));
                    })
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start() {
        try {
            ratpackServer.start();
            LOGGER.info("Listening on port {}", 35729);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        try {
            fileWatchStream.unsubscribe();
            ratpackServer.stop();
            LOGGER.info("Stopped");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class LiveReloadWebSocketHandler implements WebSocketHandler<Object> {
        private final static JsonFactory jsonFactory = new JsonFactory();
        private final ConcurrentHashMap<Object, WebSocket> websockets = new ConcurrentHashMap<>();

        public LiveReloadWebSocketHandler(Observable<String> sink) {
            sink.doOnNext(path -> LOGGER.debug("Reload '{}'", path))
                .map(LiveReloadWebSocketHandler::reload)
                .subscribe(m -> websockets.forEachValue(10, websocket -> websocket.send(m)));
        }

        @Override
        public Object onOpen(WebSocket webSocket) throws Exception {
            String id = UUID.randomUUID().toString();
            LOGGER.debug("Websocket({}) open", id);
            websockets.put(id, webSocket);
            return id;
        }

        @Override
        public void onClose(WebSocketClose<Object> close) throws Exception {
            LOGGER.debug("Websocket({}) closed - fromClient:{} fromServer:{}",
                close.getOpenResult(),
                close.isFromClient(),
                close.isFromServer());
            websockets.remove(close.getOpenResult());
        }

        @Override
        public void onMessage(WebSocketMessage<Object> frame) throws Exception {
            if (isHello(frame.getText())) {
                LOGGER.debug("Websocket({}) received hello, replying hello", frame.getOpenResult());
                frame.getConnection().send(hello());
            } else {
                LOGGER.debug("Websocket({}) received unknown command : {}", frame.getOpenResult(), frame.getText());
            }
        }

        public static String hello() {
            StringWriter stringWriter = new StringWriter();
            try (JsonGenerator generator = jsonFactory.createGenerator(stringWriter)) {
                generator.writeStartObject();
                generator.writeStringField("command", "hello");
                generator.writeArrayFieldStart("protocols");
                generator.writeString("http://livereload.com/protocols/official-7");
                generator.writeEndArray();
                generator.writeStringField("serverName", "ratpack-livereload");
                generator.writeEndObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return stringWriter.getBuffer().toString();
        }

        public static String alert(String msg) {
            StringWriter stringWriter = new StringWriter();
            try (JsonGenerator generator = jsonFactory.createGenerator(stringWriter)) {
                generator.writeStartObject();
                generator.writeStringField("command", "alert");
                generator.writeStringField("message", msg);
                generator.writeEndObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return stringWriter.getBuffer().toString();
        }

        public static String reload(String path) {
            StringWriter stringWriter = new StringWriter();
            try (JsonGenerator generator = jsonFactory.createGenerator(stringWriter)) {
                generator.writeStartObject();
                generator.writeStringField("command", "reload");
                generator.writeStringField("path", path);
                generator.writeBooleanField("liveCSS", true);
                generator.writeEndObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return stringWriter.getBuffer().toString();
        }

        public static boolean isHello(String data) {
            try {
                JsonParser parser = jsonFactory.createParser(data);
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Hum humm....");
                }

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldname = parser.getCurrentName();

                    if (fieldname.equals("command")) {
                        parser.nextToken();
                        String command = parser.getText();
                        return command.equals("hello");
                    }

                    parser.nextToken();
                }
                return false;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static class Watcher {
        private final Path baseDir;
        private final Observer<? super String> observer;
        private final Subscription subscription;
        private Map<WatchKey, Path> keyToDir;

        public Watcher(Path baseDir, Observer<? super String> observer, Subscription subscription) {
            this.baseDir = baseDir;
            this.observer = observer;
            this.subscription = subscription;
            this.keyToDir = new HashMap<>();
        }

        public void run() {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                register(baseDir, watcher);

                while (!subscription.isUnsubscribed()) {
                    WatchKey key = watcher.take();
                    Path dir = keyToDir.get(key);
                    if (dir == null) {
                        LOGGER.error("Unknown WatchKey");
                        continue;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            LOGGER.warn("Overflow Event");
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;

                        Path name = ev.context();
                        Path child = dir.resolve(name);

                        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            observer.onNext(baseDir.relativize(child).toString());
                        } else if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                                register(child, watcher);
                            }
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        keyToDir.remove(key);
                    }
                }
            } catch (IOException e) {
                observer.onError(e);
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                observer.onCompleted();
            }
        }

        private void register(Path current, WatchService watchService) throws IOException {
            Files.walkFileTree(current, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs)
                    throws IOException {
                    LOGGER.debug("Register dir {}", subdir);
                    WatchKey key = current.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                    keyToDir.put(key, current);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
