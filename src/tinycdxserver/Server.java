package tinycdxserver;

import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.*;
import java.net.ServerSocket;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.Map;

public class Server extends NanoHTTPD {
    private final DataStore manager;
    boolean verbose = false;

    public Server(DataStore manager, String hostname, int port) {
        super(hostname, port);
        this.manager = manager;
    }

    public Server(DataStore manager, ServerSocket socket) {
        super(socket);
        this.manager = manager;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (session.getUri().equals("/")) {
                return new Response("tinycdxserver running\n");
            } else if (session.getMethod().equals(Method.GET)) {
                return query(session);
            } else if (session.getMethod().equals(Method.POST)) {
                return post(session);
            }
            return new Response(Response.Status.NOT_FOUND, "text/plain", "Not found\n");
        } catch (Exception e) {
            e.printStackTrace();
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString() + "\n");
        }
    }

    Response post(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final Index index = manager.getIndex(collection, true);
        WriteBatch batch = new WriteBatch();
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long added = 0;
        for (;;) {
            String line = in.readLine();
            if (verbose) {
                System.out.println(line);
            }
            if (line == null) break;
            if (line.startsWith(" CDX")) continue;
            try {
                String[] fields = line.split(" ");
                Capture capture = new Capture();
                capture.timestamp = Long.parseLong(fields[1]);
                capture.original = fields[2];
                capture.urlkey = UrlCanonicalizer.surtCanonicalize(capture.original);
                capture.mimetype = fields[3];
                capture.status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
                capture.digest = fields[5];
                capture.redirecturl = fields[6];
                // TODO robots = fields[7]
                capture.length = fields[8].equals("-") ? 0 : Long.parseLong(fields[8]);
                capture.compressedoffset = Long.parseLong(fields[9]);
                capture.file = fields[10];
                batch.put(capture.encodeKey(), capture.encodeValue());
                added++;
            } catch (Exception e) {
                return new Response(Response.Status.BAD_REQUEST, "text/plain", e.toString() + "\nAt line: " + line);
            }
        }
        WriteOptions options = new WriteOptions();
        options.setSync(true);
        try {
            index.db.write(options, batch);
        } catch (RocksDBException e) {
            e.printStackTrace();
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
        }
        return new Response(Response.Status.OK, "text/plain", "Added " + added + " records\n");
    }

    Response query(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final Index index = manager.getIndex(collection);
        if (index == null) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "Collection does not exist\n");
        }

        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        }
        final String url = UrlCanonicalizer.surtCanonicalize(params.get("url"));

        return new Response(Response.Status.OK, "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream));
            for (Capture capture : index.query(url)) {
                out.append(capture.toString()).append('\n');
            }
            out.flush();
        });
    }

    public static void usage() {
        System.err.println("Usage: java " + Server.class.getName() + " [options...]");
        System.err.println("");
        System.err.println("  -b bindaddr   Bind to a particular IP address");
        System.err.println("  -d datadir    Directory to store index data under");
        System.err.println("  -i            Inherit the server socket via STDIN (for use with systemd, inetd etc)");
        System.err.println("  -p port       Local port to listen on");
        System.err.println("  -v            Verbose logging");
        System.exit(1);
    }

    public static void main(String args[]) {
        String host = null;
        int port = 8080;
        boolean inheritSocket = false;
        File dataPath = new File("data");
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-b":
                    host = args[++i];
                    break;
                case "-i":
                    inheritSocket = true;
                    break;
                case "-d":
                    dataPath = new File(args[++i]);
                    break;
                case "-v":
                    verbose = true;
                    break;
                default:
                    usage();
                    break;
            }
        }

        try (DataStore dataStore = new DataStore(dataPath)) {
            final Server server;
            Channel channel = System.inheritedChannel();
            if (inheritSocket && channel != null && channel instanceof ServerSocketChannel) {
                server = new Server(dataStore, ((ServerSocketChannel) channel).socket());
            } else {
                server = new Server(dataStore, host, port);
            }
            server.verbose = verbose;
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                dataStore.close();
            }));
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }
}
