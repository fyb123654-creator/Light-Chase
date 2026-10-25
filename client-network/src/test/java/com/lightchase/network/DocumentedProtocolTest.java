package com.lightchase.network;

import com.lightchase.contracts.ClientGateway;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.io.ByteArrayOutputStream;
import javax.tools.ToolProvider;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Reads the actual handoff document, not a separately maintained JSON fixture. */
public class DocumentedProtocolTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String document() throws Exception {
        String root = System.getProperty("lightchase.repoRoot");
        assertNotNull("Gradle must supply the repository root independently of the working directory", root);
        return Files.readString(Paths.get(root, "server/B7_实现与交接.md"), StandardCharsets.UTF_8);
    }

    @Test public void everyRealJsonExampleInB7PassesTheCurrentDirectionalCodec() throws Exception {
        Matcher blocks = Pattern.compile("(?ms)^```json\\s*\\R(.*?)^```[ \\t]*$").matcher(document());
        Set<String> clientTypes = new HashSet<>(Arrays.asList("HELLO", "JOIN_ROOM", "SELECT_ROLE", "SET_READY", "INPUT", "HEARTBEAT", "LEAVE_ROOM", "RETURN_LOBBY", "RESULT_ACK"));
        Set<String> seen = new TreeSet<>(); List<String> failures = new ArrayList<>();
        WireCodec codec = new WireCodec(); int count = 0;
        while (blocks.find()) {
            count++; String json = blocks.group(1);
            try {
                WireCodec.Message envelope = codec.decode(json); seen.add(envelope.type);
                if (clientTypes.contains(envelope.type)) codec.decodeClient(json); else codec.decodeServer(json);
            } catch (RuntimeException ex) { failures.add("JSON block " + count + ": " + ex.getClass().getSimpleName() + " " + ex.getMessage()); }
        }
        assertTrue("B7 must retain the real protocol examples", count >= 14);
        assertTrue("Actual document JSON failures: " + failures, failures.isEmpty());
        assertTrue(seen.containsAll(Arrays.asList("HELLO", "WELCOME", "JOIN_ROOM", "ACK", "ROOM_STATE", "SELECT_ROLE", "SET_READY", "INPUT", "SNAPSHOT", "GAME_OVER", "RESULT_ACK", "RETURN_LOBBY")));
        System.out.println("B7 actual Markdown JSON examples validated: " + count + "; types=" + seen);
    }

    @Test public void actualDocumentedInputCallsCompileAgainstCurrentContracts() throws Exception {
        Matcher calls = Pattern.compile("gateway\\.submitInput\\([^\\r\\n;`]*\\)").matcher(document());
        StringBuilder statements = new StringBuilder(); int count = 0;
        while (calls.find()) { statements.append(calls.group()).append(";\n"); count++; }
        assertTrue("Keep an actual submitInput example in B7", count > 0);
        String source = "import com.lightchase.contracts.*; class DocumentedInputCall { void sample(ClientGateway gateway, double moveX, double moveY, boolean interactHeld, boolean dropPressed) {\n" + statements + "} }";
        Path file = temporary.newFile("DocumentedInputCall.java").toPath(); Files.writeString(file, source, StandardCharsets.UTF_8);
        String contractClasses = Paths.get(ClientGateway.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        assertNotNull("This test runs on the configured JDK", ToolProvider.getSystemJavaCompiler());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-classpath", contractClasses, "-d", temporary.getRoot().toString(), file.toString());
        assertEquals("Actual document call does not compile: " + diagnostics.toString(StandardCharsets.UTF_8), 0, exit);
        System.out.println("B7 actual submitInput calls compiled: " + count);
    }
}
