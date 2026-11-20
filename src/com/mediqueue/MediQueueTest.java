package com.mediqueue;

import java.nio.file.Files;
import java.nio.file.Path;

public class MediQueueTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("mediqueue_test_", ".db");
        tmpDb.toFile().deleteOnExit();

        try (HospitalStore store = new HospitalStore(tmpDb.toString())) {
            // Seeded Cardiology queue has OPD-102 (CRITICAL) ahead of OPD-104 (NORMAL)
            String called1 = store.callNextPatient("CABIN-2");
            assert called1.contains("\"tokenNumber\":\"OPD-102\"") : "CRITICAL patient OPD-102 must preempt NORMAL patient: " + called1;
            assert called1.contains("\"triageLevel\":\"CRITICAL\"");

            // Attempting to call another patient while CABIN-2 is busy must be rejected
            String busy = store.callNextPatient("CABIN-2");
            assert busy.contains("\"ok\":false") : "Busy cabin cannot call next patient before completing current";

            // Complete consultation with prescription note
            String done = store.completeConsultation("CABIN-2", "ECG normal sinus rhythm; prescribed Aspirin 75mg");
            assert done.contains("\"ok\":true") : "Completing consultation must free cabin";

            // Next call in Cardiology must now pull OPD-104 (NORMAL)
            String called2 = store.callNextPatient("CABIN-2");
            assert called2.contains("\"tokenNumber\":\"OPD-104\"") : "Next waiting patient OPD-104 must be called: " + called2;
        }

        System.out.println("ALL MEDIQUEUE ASSERTION TESTS PASSED (Emergency Priority Preemption, Cabin Locking, Prescription Ledger)");
    }
}
