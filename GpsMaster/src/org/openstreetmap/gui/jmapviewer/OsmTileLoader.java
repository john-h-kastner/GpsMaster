// License: GPL. For details, see Readme.txt file.
package org.openstreetmap.gui.jmapviewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;

/**
 * A {@link TileLoader} implementation that loads tiles from OSM.
 *
 * @author Jan Peter Stotz
 */
public class OsmTileLoader implements TileLoader {
    private static final long maxAgeOfCacheEntry = 60L * 60L * 24L * 28L; /* 28 days */
    private static boolean debug = false;
    private static final ThreadPoolExecutor jobDispatcher = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);

    private final class OsmTileJob implements TileJob {
        private final Tile tile;
        private InputStream input;
        private boolean force;

        private OsmTileJob(Tile tile) {
            this.tile = tile;
        }

        private InputStream getInputStreamFromDiskCache(String cachedFilePath, boolean oldIsOk) {
            try {
                if (cachedFilePath == null) {
                    return null;
                }
                File file = new File(cachedFilePath);
                if (!file.exists()) {
                    return null;
                }
                if (debug) System.out.println("OsmTileLoader: found on disk=" + cachedFilePath
                                              + " oldIsOk=" + oldIsOk);
                if (oldIsOk) {
                    return new FileInputStream(file);
                }
           
                Path path = file.toPath();
                BasicFileAttributes attributes;
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
                FileTime fileTime = attributes.lastModifiedTime();
                long fileMillisec = fileTime.toMillis();
                long nowMilleSec = System.currentTimeMillis();
                long ageOfCacheEntry = (nowMilleSec - fileMillisec) / 1000;
                boolean outDated = ageOfCacheEntry > maxAgeOfCacheEntry;
                if (debug) System.out.println("OsmTileLoader: cachedFilePath=" + cachedFilePath
                                              + " ageOfCacheEntry=" + ageOfCacheEntry
                                              + " maxAgeOfCacheEntry=" + maxAgeOfCacheEntry
                                              + " outDated=" + outDated);
                if (outDated) {
                    return null;
                }
                return new FileInputStream(file);
            } catch (IOException e)  {
                e.printStackTrace();
            }
            return null;
        }
   

        @Override
        public void run() {
            synchronized (tile) {
                if ((tile.isLoaded() && !tile.hasError()) || tile.isLoading())
                    return;
                tile.loaded = false;
                tile.error = false;
                tile.loading = true;
            }
            String cachedFilePath = null;
            try {
                cachedFilePath = tile.getCachedFilePath();
                /* Load from disk cache, if it is not too old */
                boolean oldIsOk = false;
                input = getInputStreamFromDiskCache(cachedFilePath, oldIsOk);
                if (input != null) {
                    tile.loadImage(input);
                    tile.setLoaded(true);
                    listener.tileLoadingFinished(tile, true);
                    return;
                }
                URLConnection conn = getUrlConnection(tile);
                if (force) {
                    conn.setUseCaches(false);
                }
                loadTileMetadata(tile, conn);
                if ("no-tile".equals(tile.getValue("tile-info"))) {
                    tile.setError("No tile at this zoom level");
                } else {
                    input = conn.getInputStream();
                    if (cachedFilePath != null) {
                        File newFile = new File(cachedFilePath);
                        newFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            byte[] buff = new byte[20 * 1024];
                            int len = input.read(buff);
                            while (len > 0) {
                                fos.write(buff, 0, len);
                                len = input.read(buff);
                            }
                            fos.close();
                            input.close();
                            input = new FileInputStream(newFile);
                        } catch (Exception e) {
                            newFile = null;
                            e.printStackTrace();
                        }
                    }
                    if (debug) System.out.println("OsmTileLoader: input=" + input);
                    tile.loadImage(input);
                    tile.setLoaded(true);
                    listener.tileLoadingFinished(tile, true);
                }
            } catch (IOException e) {
                try {
                    /* Try to use an old cache entry */
                    boolean oldIsOk = true;
                    input = getInputStreamFromDiskCache(cachedFilePath, oldIsOk);
                    if (input != null) {
                        tile.loadImage(input);
                        tile.setLoaded(true);
                        listener.tileLoadingFinished(tile, true);
                        return;
                    }
                } catch (IOException e2)  {
                    e2.printStackTrace();
                }
                tile.setError(e.getMessage());
                listener.tileLoadingFinished(tile, false);
                if (input == null) {
                    try {
                        System.err.println("Failed loading " + tile.getUrl() +": "
                                           +e.getClass() + ": " + e.getMessage());
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }
            } finally {
                tile.loading = false;
                tile.setLoaded(true);
            }
        }

        @Override
        public Tile getTile() {
            return tile;
        }

        @Override
        public void submit() {
            submit(false);
        }

        @Override
        public void submit(boolean force) {
            this.force = force;
            jobDispatcher.execute(this);
        }
    }

    /**
     * Holds the HTTP headers. Insert e.g. User-Agent here when default should not be used.
     */
    public Map<String, String> headers = new HashMap<>();

    public int timeoutConnect;
    public int timeoutRead;

    protected TileLoaderListener listener;

    public OsmTileLoader(TileLoaderListener listener) {
        this(listener, null);
    }

    public OsmTileLoader(TileLoaderListener listener, Map<String, String> headers) {
        this.headers.put("Accept", "text/html, image/png, image/jpeg, image/gif, */*");
        this.headers.put("User-Agent", "GpsMaster.jar");
        if (headers != null) {
            this.headers.putAll(headers);
        }
        this.listener = listener;
    }

    @Override
    public TileJob createTileLoaderJob(final Tile tile) {
        return new OsmTileJob(tile);
    }

    protected URLConnection getUrlConnection(Tile tile) throws IOException {
        URL url;
        url = new URL(tile.getUrl());
        URLConnection urlConn = url.openConnection();
        if (urlConn instanceof HttpURLConnection) {
            prepareHttpUrlConnection((HttpURLConnection) urlConn);
        }
        return urlConn;
    }

    protected void loadTileMetadata(Tile tile, URLConnection urlConn) {
        String str = urlConn.getHeaderField("X-VE-TILEMETA-CaptureDatesRange");
        if (str != null) {
            tile.putValue("capture-date", str);
        }
        str = urlConn.getHeaderField("X-VE-Tile-Info");
        if (str != null) {
            tile.putValue("tile-info", str);
        }

        Long lng = urlConn.getExpiration();
        if (lng.equals(0L)) {
            try {
                str = urlConn.getHeaderField("Cache-Control");
                if (str != null) {
                    for (String token: str.split(",")) {
                        if (token.startsWith("max-age=")) {
                            lng = Long.parseLong(token.substring(8)) * 1000 +
                                System.currentTimeMillis();
                        }
                    }
                }
            } catch (NumberFormatException e) {
                // ignore malformed Cache-Control headers
                if (JMapViewer.debug) {
                    System.err.println(e.getMessage());
                }
            }
        }
        if (!lng.equals(0L)) {
            tile.putValue("expires", lng.toString());
        }
    }

    protected void prepareHttpUrlConnection(HttpURLConnection urlConn) {
        for (Entry<String, String> e : headers.entrySet()) {
            urlConn.setRequestProperty(e.getKey(), e.getValue());
        }
        if (timeoutConnect != 0)
            urlConn.setConnectTimeout(timeoutConnect);
        if (timeoutRead != 0)
            urlConn.setReadTimeout(timeoutRead);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    @Override
    public void cancelOutstandingTasks() {
        jobDispatcher.getQueue().clear();
    }

    /**
     * Sets the maximum number of concurrent connections the tile loader will do
     * @param num number of conncurent connections
     */
    public static void setConcurrentConnections(int num) {
        jobDispatcher.setMaximumPoolSize(num);
    }
}
