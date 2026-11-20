package com.mediqueue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class HospitalStore implements AutoCloseable {
    private final Connection conn;

    public HospitalStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS cabins (
                    cabin_code TEXT PRIMARY KEY,
                    doctor_name TEXT NOT NULL,
                    department TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'READY',
                    current_token TEXT
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS queue_tickets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    token_number TEXT NOT NULL UNIQUE,
                    patient_name TEXT NOT NULL,
                    age INTEGER NOT NULL,
                    department TEXT NOT NULL,
                    symptoms TEXT NOT NULL,
                    triage_level TEXT NOT NULL,
                    priority_rank INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'WAITING',
                    assigned_cabin TEXT,
                    prescription_notes TEXT DEFAULT '',
                    created_at TEXT NOT NULL,
                    completed_at TEXT
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_queue_priority ON queue_tickets(status, department, priority_rank ASC, id ASC);");
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cabins")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] initialCabins = {
            {"CABIN-1", "Dr. Arun Prakash", "General Medicine"},
            {"CABIN-2", "Dr. Meera Nair", "Cardiology"},
            {"CABIN-3", "Dr. Karthik Sundar", "Orthopedics"}
        };

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO cabins (cabin_code, doctor_name, department, status) VALUES (?, ?, ?, 'READY')")) {
            for (Object[] c : initialCabins) {
                ps.setString(1, (String) c[0]);
                ps.setString(2, (String) c[1]);
                ps.setString(3, (String) c[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Seed realistic initial patients
        registerPatient("Ramesh Kumar", 54, "General Medicine", "Persistent fever & cough", "NORMAL");
        registerPatient("Priya Lakshmi", 62, "Cardiology", "Chest tightness & palpitations", "CRITICAL");
        registerPatient("Suresh Babu", 38, "Orthopedics", "Acute ankle sprain", "URGENT");
        registerPatient("Divya Sri", 27, "Cardiology", "Routine ECG follow-up", "NORMAL");
    }

    private int rankForTriage(String triageLevel) {
        return switch (triageLevel.toUpperCase()) {
            case "CRITICAL" -> 1;
            case "URGENT" -> 2;
            default -> 3;
        };
    }

    public synchronized String registerPatient(String patientName, int age, String department, String symptoms, String triageLevel) throws SQLException {
        String normalizedTriage = triageLevel == null ? "NORMAL" : triageLevel.toUpperCase();
        if (!normalizedTriage.equals("CRITICAL") && !normalizedTriage.equals("URGENT") && !normalizedTriage.equals("NORMAL")) {
            normalizedTriage = "NORMAL";
        }
        int rank = rankForTriage(normalizedTriage);

        conn.setAutoCommit(false);
        try {
            long insertedId;
            String tempToken = "TMP-" + System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                    INSERT INTO queue_tickets (token_number, patient_name, age, department, symptoms, triage_level, priority_rank, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, tempToken);
                ps.setString(2, patientName);
                ps.setInt(3, Math.max(1, age));
                ps.setString(4, department);
                ps.setString(5, symptoms);
                ps.setString(6, normalizedTriage);
                ps.setInt(7, rank);
                ps.setString(8, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    insertedId = keys.next() ? keys.getLong(1) : 1L;
                }
            }

            String finalToken = String.format("OPD-%03d", 100 + insertedId);
            try (PreparedStatement upd = conn.prepareStatement("UPDATE queue_tickets SET token_number = ? WHERE id = ?")) {
                upd.setString(1, finalToken);
                upd.setLong(2, insertedId);
                upd.executeUpdate();
            }

            conn.commit();
            return String.format("{\"ok\":true,\"tokenNumber\":\"%s\",\"triageLevel\":\"%s\"}", finalToken, normalizedTriage);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String callNextPatient(String cabinCode) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String department;
            String cabinStatus;
            try (PreparedStatement ps = conn.prepareStatement("SELECT department, status FROM cabins WHERE cabin_code = ?")) {
                ps.setString(1, cabinCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Cabin not found\"}";
                    }
                    department = rs.getString("department");
                    cabinStatus = rs.getString("status");
                }
            }

            if (!"READY".equals(cabinStatus)) {
                conn.rollback();
                return "{\"ok\":false,\"error\":\"Complete current consultation in " + JsonUtil.escape(cabinCode) + " first\"}";
            }

            // Pick highest-priority waiting patient in that department (CRITICAL -> URGENT -> NORMAL)
            long ticketId;
            String tokenNumber;
            String patientName;
            String triageLevel;
            try (PreparedStatement find = conn.prepareStatement(
                    """
                    SELECT id, token_number, patient_name, triage_level
                    FROM queue_tickets
                    WHERE status = 'WAITING' AND department = ?
                    ORDER BY priority_rank ASC, id ASC
                    LIMIT 1
                    """)) {
                find.setString(1, department);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"No waiting patients in " + JsonUtil.escape(department) + "\"}";
                    }
                    ticketId = rs.getLong("id");
                    tokenNumber = rs.getString("token_number");
                    patientName = rs.getString("patient_name");
                    triageLevel = rs.getString("triage_level");
                }
            }

            try (PreparedStatement updT = conn.prepareStatement(
                    "UPDATE queue_tickets SET status = 'IN_CONSULTATION', assigned_cabin = ? WHERE id = ?")) {
                updT.setString(1, cabinCode);
                updT.setLong(2, ticketId);
                updT.executeUpdate();
            }

            try (PreparedStatement updC = conn.prepareStatement(
                    "UPDATE cabins SET status = 'IN_CONSULTATION', current_token = ? WHERE cabin_code = ?")) {
                updC.setString(1, tokenNumber);
                updC.setString(2, cabinCode);
                updC.executeUpdate();
            }

            conn.commit();
            return String.format(
                "{\"ok\":true,\"tokenNumber\":\"%s\",\"patientName\":\"%s\",\"triageLevel\":\"%s\",\"cabinCode\":\"%s\"}",
                JsonUtil.escape(tokenNumber), JsonUtil.escape(patientName), JsonUtil.escape(triageLevel), JsonUtil.escape(cabinCode)
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String completeConsultation(String cabinCode, String notes) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String tokenNumber;
            try (PreparedStatement ps = conn.prepareStatement("SELECT current_token FROM cabins WHERE cabin_code = ? AND status = 'IN_CONSULTATION'")) {
                ps.setString(1, cabinCode);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString("current_token") == null) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"No active consultation in " + JsonUtil.escape(cabinCode) + "\"}";
                    }
                    tokenNumber = rs.getString("current_token");
                }
            }

            try (PreparedStatement updT = conn.prepareStatement(
                    "UPDATE queue_tickets SET status = 'COMPLETED', prescription_notes = ?, completed_at = ? WHERE token_number = ?")) {
                updT.setString(1, notes == null || notes.isBlank() ? "Standard OPD observation & Rx issued" : notes);
                updT.setString(2, Instant.now().toString());
                updT.setString(3, tokenNumber);
                updT.executeUpdate();
            }

            try (PreparedStatement updC = conn.prepareStatement(
                    "UPDATE cabins SET status = 'READY', current_token = NULL WHERE cabin_code = ?")) {
                updC.setString(1, cabinCode);
                updC.executeUpdate();
            }

            conn.commit();
            return "{\"ok\":true,\"completedToken\":\"" + JsonUtil.escape(tokenNumber) + "\"}";
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        int waitingCount = 0, criticalWaiting = 0, inConsult = 0, completedCount = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, triage_level, COUNT(*) AS cnt FROM queue_tickets GROUP BY status, triage_level")) {
            while (rs.next()) {
                String s = rs.getString("status");
                String t = rs.getString("triage_level");
                int c = rs.getInt("cnt");
                if ("WAITING".equals(s)) {
                    waitingCount += c;
                    if ("CRITICAL".equals(t)) criticalWaiting += c;
                } else if ("IN_CONSULTATION".equals(s)) {
                    inConsult += c;
                } else if ("COMPLETED".equals(s)) {
                    completedCount += c;
                }
            }
        }

        List<String> cabinList = new ArrayList<>();
        String cabinSql = """
            SELECT c.cabin_code, c.doctor_name, c.department, c.status, c.current_token,
                   q.patient_name, q.age, q.triage_level, q.symptoms
            FROM cabins c
            LEFT JOIN queue_tickets q ON c.current_token = q.token_number
            ORDER BY c.cabin_code ASC
        """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(cabinSql)) {
            while (rs.next()) {
                cabinList.add(String.format(
                    "{\"cabinCode\":\"%s\",\"doctorName\":\"%s\",\"department\":\"%s\",\"status\":\"%s\",\"currentToken\":\"%s\",\"patientName\":\"%s\",\"age\":%d,\"triageLevel\":\"%s\",\"symptoms\":\"%s\"}",
                    JsonUtil.escape(rs.getString("cabin_code")),
                    JsonUtil.escape(rs.getString("doctor_name")),
                    JsonUtil.escape(rs.getString("department")),
                    JsonUtil.escape(rs.getString("status")),
                    JsonUtil.escape(rs.getString("current_token") != null ? rs.getString("current_token") : ""),
                    JsonUtil.escape(rs.getString("patient_name") != null ? rs.getString("patient_name") : ""),
                    rs.getInt("age"),
                    JsonUtil.escape(rs.getString("triage_level") != null ? rs.getString("triage_level") : ""),
                    JsonUtil.escape(rs.getString("symptoms") != null ? rs.getString("symptoms") : "")
                ));
            }
        }

        List<String> waitingList = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT token_number, patient_name, age, department, symptoms, triage_level FROM queue_tickets WHERE status = 'WAITING' ORDER BY priority_rank ASC, id ASC")) {
            while (rs.next()) {
                waitingList.add(String.format(
                    "{\"tokenNumber\":\"%s\",\"patientName\":\"%s\",\"age\":%d,\"department\":\"%s\",\"symptoms\":\"%s\",\"triageLevel\":\"%s\"}",
                    JsonUtil.escape(rs.getString("token_number")),
                    JsonUtil.escape(rs.getString("patient_name")),
                    rs.getInt("age"),
                    JsonUtil.escape(rs.getString("department")),
                    JsonUtil.escape(rs.getString("symptoms")),
                    JsonUtil.escape(rs.getString("triage_level"))
                ));
            }
        }

        List<String> completedList = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT token_number, patient_name, department, prescription_notes FROM queue_tickets WHERE status = 'COMPLETED' ORDER BY id DESC LIMIT 6")) {
            while (rs.next()) {
                completedList.add(String.format(
                    "{\"tokenNumber\":\"%s\",\"patientName\":\"%s\",\"department\":\"%s\",\"notes\":\"%s\"}",
                    JsonUtil.escape(rs.getString("token_number")),
                    JsonUtil.escape(rs.getString("patient_name")),
                    JsonUtil.escape(rs.getString("department")),
                    JsonUtil.escape(rs.getString("prescription_notes"))
                ));
            }
        }

        return String.format(
            "{\"metrics\":{\"waiting\":%d,\"criticalWaiting\":%d,\"inConsultation\":%d,\"completed\":%d},\"cabins\":[%s],\"waitingQueue\":[%s],\"completedHistory\":[%s]}",
            waitingCount, criticalWaiting, inConsult, completedCount,
            String.join(",", cabinList),
            String.join(",", waitingList),
            String.join(",", completedList)
        );
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
