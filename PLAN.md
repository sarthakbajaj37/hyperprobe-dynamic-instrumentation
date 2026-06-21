# HyperProbe Assignment — Build Plan

**Dynamic Instrumentation Proof of Concept**
**Chosen stack:** Java (Spring Boot target) + JDWP/JDI agent (Java Debug Wire Protocol / Java Debug Interface)

---

## 1. Why this approach fits the brief

The assignment wants external instrumentation that reads local variables and walks the call stack **without touching the target's source**. The JVM ships a purpose-built mechanism for exactly this: the **Java Debug Wire Protocol (JDWP)**, driven from a separate process through the **Java Debug Interface (JDI, `com.sun.jdi`)**.

The key insight for the "No Code Modification" constraint: the target is launched with a JVM runtime flag (`-agentlib:jdwp=...`). That is a launch-time flag, **not** a source edit, not a recompile with business logic, not an injected log line. The target `.java` files stay pristine and contain zero observability code. The agent is a completely separate program that attaches over a socket. This gives a very clean "separation of concerns" story for the evaluation.

JDI is part of the **JDK** (module `jdk.jdi`) — no third-party dependency required for the core mechanism. The target uses Spring Boot, which the brief explicitly permits ("established community libraries"). The constraint is about no *observability* code in the target, not no frameworks — so Spring is fine **as long as we do not pull in Spring Boot Actuator or any tracing/metrics starters**. The target is written as if observability does not exist.

---

## 2. Component A — Target Application (Spring Boot HTTP Calculator)

A standard Spring Boot REST app (Spring Web only — **no Actuator, no Micrometer, no tracing starters**).

- **Endpoint:** `GET /calculate?op=add&a=10&b=20` on Tomcat (default port 8080).
- **Call chain (≥3 nested methods, as required), all Spring-managed beans:**
  `CalculatorController.calculate()` → `MathService.compute()` → `AdditionEngine.add()` (and `SubtractionEngine`, `MultiplicationEngine`, etc.)
- Each layer holds its own local variables (parsed params, intermediate values) so there is real state to capture at each stack frame.
- **Strict constraint honored:** absolutely no logging, tracing, metrics, or data-collection code anywhere in this component. Written as if observability does not exist.

> **Spring proxy note:** keep these beans plain `@Service`/`@Component` with **no AOP, `@Transactional`, or `@Async`** on the calc path. That way Spring calls the real implementation classes directly (no CGLIB/JDK proxy in between), so JDI breakpoints set on the concrete `Class.method` resolve and fire reliably. If a proxied method were targeted, the breakpoint would need to land on the concrete implementation, not the proxy.

Suggested package layout:

```
target/src/main/java/com/hyperprobe/target/
  TargetApplication.java   // @SpringBootApplication main
  CalculatorController.java // @RestController, /calculate
  MathService.java          // @Service, dispatches to the right engine
  AdditionEngine.java       // @Component
  SubtractionEngine.java
  ...
target/pom.xml              // spring-boot-starter-web only
```

---

## 3. Component B — Instrumentation Agent

A separate Java program (its own `main`) that uses JDI to attach and inspect.

**Attach**
- Target JVM started with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`.
- Agent uses JDI's `SocketAttachingConnector` to connect to `host:5005` and obtain a `VirtualMachine` handle.

**Dynamic breakpoints** (accepted at runtime via CLI args / a small config — no recompiling the agent for a new breakpoint)
- Accept either `ClassName.methodName` **or** `file:lineNumber`.
- Resolve the `ReferenceType` for the class, find the `Location` (method entry location, or `locationsOfLine(n)`), and register a `BreakpointRequest` via the `EventRequestManager`.
- Set suspend policy to **`SUSPEND_EVENT_THREAD`** (suspend only the thread that hit it, not the whole VM).

**State extraction** (on `BreakpointEvent`)
- Get the `ThreadReference`, then `thread.frames()` to walk the full call stack from the breakpoint up through every caller.
- For each `StackFrame`:
  - `frame.location()` → class, method, line.
  - `frame.visibleVariables()` + `frame.getValues(...)` → **local variable names and values in scope**.
  - `frame.thisObject()` → instance (`this`) state where relevant.
- Serialize primitives directly; render objects with type + a bounded summary to avoid runaway recursion.

**Output**
- Emit captured state as JSON to **stdout** and/or a `captures/*.json` file.
- **Immediately resume** the suspended thread after capture so the HTTP request completes normally. Because only the event thread was suspended, the server keeps serving other requests throughout. This directly satisfies "without crashing or halting the target's ability to respond."

---

## 4. Containerization (Dockerfile)

- **Base image:** `eclipse-temurin:21-jdk` (full JDK — the JRE alone does **not** include the `jdk.jdi` module the agent needs).
- **Build:** **Maven (or Gradle)**, the Spring Boot standard. Build the target as a Spring Boot fat jar (`spring-boot-maven-plugin`); build the agent as its own jar. A multi-stage Dockerfile compiles both in stage 1 and copies the jars into a slim runtime stage.
- **Entrypoint script** for a seamless single-command run:
  1. Launch the Spring Boot fat jar with the JDWP flag: `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar target-app.jar`.
  2. Wait until the app is **ready** (poll the `/calculate` endpoint, not just the port — Spring context startup takes longer than a raw socket open).
  3. Launch the agent jar → it attaches over JDWP and arms the breakpoint(s).
  4. Container is ready; user curls the endpoint and sees the JSON capture printed.
- Single container keeps reproduction to `docker build` + `docker run` with no local Java needed. (A `docker-compose` two-service variant is an optional alternative if separate processes/containers are preferred.)

**Build-cache tip:** copy `pom.xml` and run `mvn dependency:go-offline` before copying source, so Spring Boot dependencies cache across rebuilds.

---

## 5. README.md — IMPORTANT

The assignment explicitly says **DO NOT use AI to generate the README**. So the plan here is only an **outline for you to write yourself, in your own words**:

- **Execution instructions:** exact `docker build` and `docker run` commands, plus a sample `curl http://localhost:8080/calculate?op=add&a=10&b=20`, and where the captured JSON appears.
- **Architecture write-up (1–2 paragraphs):** how the agent attaches via JDWP and uses JDI to read frames/locals; why the target stays unmodified.
- **Limitations + production improvements** (see section 6).
- **Anything else relevant:** the breakpoint config format, design trade-offs.

I can help you *test* the commands and confirm they're correct, but the prose should be yours to stay within the rules.

---

## 6. Limitations & production improvements (for the write-up and "System Empathy" criterion)

**Limitations of the PoC**
- JDWP attach can push the JVM toward interpreted/less-optimized execution; meaningful runtime overhead.
- Suspending the event thread (even briefly) adds latency to that request; a breakpoint on a hot path would be costly.
- Open debug port (5005) is a security exposure if reachable beyond localhost.
- Deep object graphs are summarized, not fully serialized, to stay safe.

**How to improve for high-traffic production**
- Move to a **JVMTI native agent** or `java.lang.instrument` + bytecode instrumentation (ASM/ByteBuddy) for far lower overhead and no thread suspension.
- **Conditional / sampled capture** (only N% of hits, or only when a predicate matches) to bound impact.
- **Asynchronous, off-thread serialization** so the request thread resumes instantly.
- Lock down the transport (localhost-only, auth, mTLS) and add capture-rate limits / circuit breakers to protect host stability.

---

## 7. Suggested build order

1. Build the Spring Boot calculator (Spring Web only, no Actuator); verify `curl` returns correct results.
2. Run the fat jar with the JDWP launch flag; confirm the JVM listens on 5005.
3. Build the agent: attach → arm a `Class.method` breakpoint → on hit, dump the top frame's locals.
4. Extend to walk the **full stack** (all caller frames) and emit structured JSON.
5. Add `file:line` breakpoint support and CLI/config for the breakpoint spec.
6. Write the Dockerfile + entrypoint; verify clean `build` → `run` → `curl` → JSON capture.
7. Verification pass: confirm the target has zero observability code, the request still returns 200 while capture happens, and the whole thing runs from a fresh clone with only Docker installed.
8. You write the README by hand.

---

## 8. Final deliverables checklist

- [ ] Git repo with target + agent source
- [ ] Spring Boot target with ≥3-deep call chain, no Actuator, zero observability code
- [ ] Agent: dynamic breakpoints, locals + full call-stack capture, JSON output, non-halting
- [ ] Dockerfile (standard base image) — seamless build & run
- [ ] Hand-written README (execution steps, architecture, limitations)
