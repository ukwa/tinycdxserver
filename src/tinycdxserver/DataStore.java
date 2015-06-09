package tinycdxserver;

import org.rocksdb.*;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements Closeable {
    private final File dataDir;
    private final Map<String, RocksDB> indexes = new ConcurrentHashMap<String, RocksDB>();

    public DataStore(File dataDir) {
        this.dataDir = dataDir;
    }

    public Index getIndex(String collection) throws IOException {
        return getIndex(collection, false);
    }

    public Index getIndex(String collection, boolean createAllowed) throws IOException {
        RocksDB db = indexes.get(collection);
        if (db != null) {
            return new Index(db);
        }
        return new Index(openDb(collection, createAllowed));
    }

    private synchronized RocksDB openDb(String collection, boolean createAllowed) throws IOException {
        if (!isValidCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name");
        }
        RocksDB index = indexes.get(collection);
        if (index != null) {
            return index;
        }
        File path = new File(dataDir, collection);
        if (!createAllowed && !path.isDirectory()) {
            return null;
        }

        try {

            Options options = new Options();
            options.createStatistics();
            options.setCreateIfMissing(true);
            options.setCompactionStyle(CompactionStyle.LEVEL);
            options.setWriteBufferSize(64 * 1024 * 1024);
            options.setTargetFileSizeBase(64 * 1024 * 1024);
            options.setMaxBytesForLevelBase(512 * 1024 * 1024);
            options.setTargetFileSizeMultiplier(2);
            options.setCompressionType(CompressionType.SNAPPY_COMPRESSION);
            index = RocksDB.open(options, path.toString());
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
        indexes.put(collection, index);
        return index;
    }

    private static boolean isValidCollectionName(String collection) {
        return collection.matches("^[A-Za-z0-9_-]+$");
    }

    public void close() {
        for (RocksDB index : indexes.values()) {
            index.close();
        }
        indexes.clear();
    }
}
