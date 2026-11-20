# MediQueue

**Real-Time Hospital OPD Triage & Waiting-Room Queue System (Java 21 + Vanilla JavaScript)**

MediQueue is a real-time outpatient department (OPD) triage and waiting-room display platform built with a **Java 21 Virtual Threads** HTTP server, **SQLite WAL** priority queue scheduling, and a live-refreshing **JavaScript** console. Emergency (`CRITICAL`) patients automatically preempt routine (`NORMAL`) queues per department, while doctor cabins enforce atomic single-patient consultation locks and digital prescription logging.

## Architecture

~~~mermaid
flowchart LR
    A[Triage Nurse / Walk-In JS UI] -->|POST /api/register| B[Java 21 Virtual Thread Server]
    B -->|Priority Rank 1-3| C[(SQLite WAL Queue DB)]
    D[Doctor Cabin Console] -->|POST /api/call-next| B
    C -->|Highest Priority Token| E[Cabin Consultation Lock]
    E -->|POST /api/complete| F[Digital Rx Ledger & Cabin Freed]
~~~

## Quickstart

~~~bash
git clone https://github.com/itsmerbk/MediQueue.git
cd MediQueue

mkdir -p out
javac -cp "lib/*" -d out src/com/mediqueue/*.java

# 1. Run automated priority-preemption & cabin locking tests
java -ea --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.mediqueue.MediQueueTest

# 2. Start the live web console (http://127.0.0.1:8081)
java --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.mediqueue.Main
~~~
