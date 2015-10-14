/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.transaction.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.lealone.storage.StorageMap;
import org.lealone.storage.fs.FilePath;
import org.lealone.storage.fs.FileUtils;
import org.lealone.storage.type.DataType;

/**
 * A log storage
 * 
 * @author zhh
 */
public class LogStorage {

    public static final char MAP_NAME_ID_SEPARATOR = '-';

    private static final String TEMP_MAP_NAME_PREFIX = "temp" + MAP_NAME_ID_SEPARATOR;

    private static final CopyOnWriteArrayList<LogMap<?, ?>> logMaps = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private final Map<String, String> config;
    private final LogStorageBackgroundThread backgroundThread;

    /**
     * The next id of a temporary map.
     */
    private int nextTempMapId;

    public LogStorage(Map<String, String> config) {
        this.config = config;
        String storageName = config.get("storageName");
        if (storageName != null) {
            if (!FileUtils.exists(storageName))
                FileUtils.createDirectories(storageName);

            FilePath dir = FilePath.get(storageName);
            for (FilePath fp : dir.newDirectoryStream()) {
                String mapFullName = fp.getName();
                if (mapFullName.startsWith(TEMP_MAP_NAME_PREFIX)) {
                    fp.delete();
                    continue;
                }

                int mapIdStartPos = mapFullName.lastIndexOf(MAP_NAME_ID_SEPARATOR);
                if (mapIdStartPos > 0) {
                    String mapName = mapFullName.substring(0, mapIdStartPos);
                    int mapId = Integer.parseInt(mapFullName.substring(mapIdStartPos + 1));
                    Integer oldMapId = ids.put(mapName, mapId);
                    if (oldMapId != null && mapId < oldMapId)
                        ids.put(mapName, oldMapId);
                }

            }
        }

        backgroundThread = new LogStorageBackgroundThread(this);
    }

    public synchronized StorageMap<Object, Integer> createTempMap() {
        String mapName = LogStorage.TEMP_MAP_NAME_PREFIX + (++nextTempMapId);
        return openLogMap(mapName, null, null);
    }

    public <K, V> LogMap<K, V> openLogMap(String name, DataType keyType, DataType valueType) {
        int mapId = 1;
        if (ids.containsKey(name))
            mapId = ids.get(name);
        LogMap<K, V> m = new LogMap<>(mapId, name, keyType, valueType, config);
        logMaps.add(m);
        return m;
    }

    public synchronized void close() {
        backgroundThread.close();

        for (StorageMap<?, ?> map : logMaps)
            map.close();

        logMaps.clear();
        ids.clear();
    }

    public synchronized void commit() {
        for (StorageMap<?, ?> map : logMaps)
            map.save();
    }

    private static class LogStorageBackgroundThread extends Thread {
        private final int sleep;
        private boolean running = true;

        LogStorageBackgroundThread(LogStorage storage) {
            super("LogStorageBackgroundThread");
            this.sleep = 1000;
            setDaemon(true);
        }

        void close() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    sleep(sleep);
                } catch (InterruptedException e) {
                    continue;
                }

                for (LogMap<?, ?> map : LogStorage.logMaps) {
                    map.save();
                }
            }
        }
    }
}