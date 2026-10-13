package atm;

import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    static Properties cfg = new Properties();
    static Map<String, String> sessions = new ConcurrentHashMap<>(); // token -> account number
    static SecureRandom rnd = new SecureRandom();

    static Connection db() throws SQLException {
        return DriverManager.getConnection(cfg.getProperty("db.url"),
                cfg.getProperty("db.user"), cfg.getProperty("db.password"));
    }

    public static void main(String[] args) throws Exception {
        try (InputStream in = new FileInputStream("config.properties")) { cfg.load(in); }
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS accounts(acc_no VARCHAR(10) PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                    + "pin_hash VARCHAR(64) NOT NULL, balance DECIMAL(12,2) NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS transactions(id INT AUTO_INCREMENT PRIMARY KEY, acc_no VARCHAR(10) NOT NULL, "
                    + "type VARCHAR(10) NOT NULL, amount DECIMAL(12,2) NOT NULL, balance_after DECIMAL(12,2) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("Connected to Aiven MySQL. Tables ready.");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/", Main::api);
        server.createContext("/", Main::staticFile);
        server.start();
        System.out.println("ATM running at http://localhost:8080");
    }

    // ---------- API ----------
    static void api(HttpExchange x) throws IOException {
        int code = 200; String out;
        try {
            out = route(x.getRequestURI().getPath(), params(x));
        } catch (IllegalArgumentException e) { code = 400; out = json("error", e.getMessage());
        } catch (Exception e) { e.printStackTrace(); code = 500; out = json("error", "Server error"); }
        send(x, code, "application/json", out.getBytes(StandardCharsets.UTF_8));
    }

    static String route(String path, Map<String, String> p) throws Exception {
        switch (path) {
            case "/api/register": {
                String name = p.getOrDefault("name", "").trim();
                String pin = p.getOrDefault("pin", "");
                if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("Enter your name");
                if (!pin.matches("\\d{4}")) throw new IllegalArgumentException("PIN must be 4 digits");
                String acc = String.format("%010d", (long) (rnd.nextDouble() * 1_000_000_0000L));
                try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO accounts(acc_no,name,pin_hash) VALUES(?,?,?)")) {
                    ps.setString(1, acc); ps.setString(2, name); ps.setString(3, hash(acc, pin));
                    ps.executeUpdate();
                }
                return json("account", acc);
            }
            case "/api/login": {
                String acc = p.getOrDefault("account", ""), pin = p.getOrDefault("pin", "");
                try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                        "SELECT name,pin_hash FROM accounts WHERE acc_no=?")) {
                    ps.setString(1, acc);
                    ResultSet r = ps.executeQuery();
                    if (!r.next() || !r.getString(2).equals(hash(acc, pin)))
                        throw new IllegalArgumentException("Wrong account number or PIN");
                    String token = UUID.randomUUID().toString();
                    sessions.put(token, acc);
                    return json("token", token, "name", r.getString(1));
                }
            }
            case "/api/logout": sessions.remove(p.get("token")); return json("ok", "1");
        }
        String acc = sessions.get(p.get("token"));
        if (acc == null) throw new IllegalArgumentException("Session expired. Please log in again.");
        switch (path) {
            case "/api/balance":
                try (Connection c = db()) { return json("balance", balance(c, acc)); }
            case "/api/deposit": return move(acc, "DEPOSIT", amount(p));
            case "/api/withdraw": return move(acc, "WITHDRAW", amount(p));
            case "/api/history":
                try (Connection c = db(); PreparedStatement ps = c.prepareStatement(
                        "SELECT type,amount,balance_after,created_at FROM transactions WHERE acc_no=? ORDER BY id DESC LIMIT 10")) {
                    ps.setString(1, acc);
                    ResultSet r = ps.executeQuery();
                    StringBuilder sb = new StringBuilder("{\"items\":[");
                    boolean first = true;
                    while (r.next()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(json("type", r.getString(1), "amount", r.getBigDecimal(2),
                                "balance", r.getBigDecimal(3), "time", r.getString(4)));
                    }
                    return sb.append("]}").toString();
                }
            default: throw new IllegalArgumentException("Unknown action");
        }
    }

    static BigDecimal amount(Map<String, String> p) {
        try {
            BigDecimal a = new BigDecimal(p.getOrDefault("amount", ""));
            if (a.signum() <= 0 || a.scale() > 2 || a.compareTo(new BigDecimal("100000")) > 0) throw new Exception();
            return a;
        } catch (Exception e) { throw new IllegalArgumentException("Enter a valid amount (max 100000)"); }
    }

    static BigDecimal balance(Connection c, String acc) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE acc_no=?")) {
            ps.setString(1, acc);
            ResultSet r = ps.executeQuery(); r.next();
            return r.getBigDecimal(1);
        }
    }

    static String move(String acc, String type, BigDecimal amt) throws SQLException {
        boolean dep = type.equals("DEPOSIT");
        try (Connection c = db()) {
            c.setAutoCommit(false);
            String sql = dep ? "UPDATE accounts SET balance=balance+? WHERE acc_no=?"
                             : "UPDATE accounts SET balance=balance-? WHERE acc_no=? AND balance>=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setBigDecimal(1, amt); ps.setString(2, acc);
                if (!dep) ps.setBigDecimal(3, amt);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Insufficient funds");
            }
            BigDecimal bal = balance(c, acc);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(acc_no,type,amount,balance_after) VALUES(?,?,?,?)")) {
                ps.setString(1, acc); ps.setString(2, type); ps.setBigDecimal(3, amt); ps.setBigDecimal(4, bal);
                ps.executeUpdate();
            }
            c.commit();
            return json("balance", bal);
        }
    }

    // ---------- helpers ----------
    static String hash(String salt, String pin) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            Object v = kv[i + 1];
            sb.append("\"").append(kv[i]).append("\":");
            if (v instanceof Number) sb.append(v);
            else sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        return sb.append("}").toString();
    }

    static Map<String, String> params(HttpExchange x) throws IOException {
        Map<String, String> m = new HashMap<>();
        String q = x.getRequestURI().getRawQuery();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String s : new String[]{q == null ? "" : q, body})
            for (String kv : s.split("&")) {
                String[] a = kv.split("=", 2);
                if (a.length == 2) m.put(URLDecoder.decode(a[0], StandardCharsets.UTF_8), URLDecoder.decode(a[1], StandardCharsets.UTF_8));
            }
        return m;
    }

    static void staticFile(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path root = Paths.get("public").toAbsolutePath().normalize();
        Path f = root.resolve(path.substring(1)).normalize();
        if (!f.startsWith(root) || !Files.isRegularFile(f)) { send(x, 404, "text/plain", "Not found".getBytes()); return; }
        String n = f.toString();
        String type = n.endsWith(".html") ? "text/html" : n.endsWith(".css") ? "text/css"
                : n.endsWith(".js") ? "application/javascript" : "application/octet-stream";
        send(x, 200, type + "; charset=utf-8", Files.readAllBytes(f));
    }

    static void send(HttpExchange x, int code, String type, byte[] b) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }
}
